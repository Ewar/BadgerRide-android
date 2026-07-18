package com.badgerride.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import com.badgerride.Mode
import java.util.UUID

/**
 * One FTMS trainer connection: service discovery, notification setup, the
 * control-point procedure chain and the ERG drive loop. Ported from the ErgPoc
 * proof of concept - see [GattOpQueue] for the queue invariants.
 *
 * FTMS treats each control point write as a procedure that is not complete until
 * its 0x80 response indication arrives, so the ERG entry sequence is chained off
 * the responses rather than written back-to-back:
 *   CP CCCD enabled -> 0x00 Request Control -> 0x07 Start/Resume ->
 *   0x05 Set Target Power (native ERG) or 0x11 sim params (emulated ERG)
 */
@SuppressLint("MissingPermission")
internal class TrainerLink(
    private val context: Context,
    val device: BluetoothDevice,
    private val handler: Handler,
    private val hub: BleCentral,
) {
    private val queue = GattOpQueue(handler) { log(it) }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var controlPoint: BluetoothGattCharacteristic? = null
    @Volatile private var haveControl = false
    @Volatile private var closing = false

    // ERG emulation state (for trainers whose feature read lacks the power target):
    @Volatile private var targetFeatures = -1      // Target Setting Features bits; -1 until read
    @Volatile private var simGrade = 0             // Indoor Bike Simulation grade, 0.01 % units
    // Grade limits in 0.01 % units; defaults until the Supported Inclination Range read arrives.
    @Volatile private var gradeMin = -1_000        // -10 %
    @Volatile private var gradeMax = 3_000         // +30 %
    @Volatile private var gradeStep = 1
    @Volatile private var lastMachineStatus = ""

    /** Bike-data notifications arrive several times a second; log the full parse this often. */
    private val BIKE_DATA_LOG_MS = 2_000L
    // Only touched from the trainer's GATT callback thread.
    private var lastBikeDataLog = 0L
    private var bikeDataCount = 0
    private var cpmCount = 0
    private var cpmWindowStart = 0L

    private val ERG_TICK_MS = 1_000L
    private val GRADE_PER_WATT = 0.5   // P gain; raise for faster convergence, lower if it oscillates

    // The FTMS spec is inconsistent here: the range characteristic is sint16 but the Set
    // Target Resistance Level parameter is spec'd as a single uint8 (both 0.1 units).
    // Firmwares implement either. Flip this if the trainer ignores resistance commands.
    private val RESISTANCE_AS_SINT16 = false

    private fun log(msg: String) = hub.log(msg)

    /** ERG entry gave up. Surfaced in the UI - the log alone is too easy to miss. */
    private fun ergFailed(reason: String) = hub.ergStatus("ERG unavailable: $reason")

    fun connect() {
        closing = false
        haveControl = false
        controlPoint = null
        simGrade = 0
        log("Connecting to trainer ${device.name ?: device.address}…")
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        closing = true
        handler.removeCallbacks(ergTicker)
        queue.flush("trainer link closed")
        haveControl = false
        gatt?.close()
        gatt = null
    }

    // ---- Queue-fronted primitives ------------------------------------------

    private fun enableNotifications(ch: BluetoothGattCharacteristic, indicate: Boolean = false,
                                    onFailed: (String) -> Unit = {}) {
        val kind = if (indicate) "indications" else "notifications"
        queue.enqueue(GattOpQueue.Op(key = ch.uuid, label = "enable $kind on ${shortUuid(ch.uuid)}",
                                     onFailed = onFailed) {
            val gatt = gatt ?: return@Op false
            val cccd = ch.getDescriptor(Ftms.CCCD) ?: return@Op false
            val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            // Without setCharacteristicNotification the CCCD write still succeeds but
            // Android never dispatches the callbacks locally - the op would "time out".
            gatt.setCharacteristicNotification(ch, true) &&
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        })
    }

    /** One-shot informational read; the value is parsed in onCharacteristicRead. */
    private fun readCharacteristic(ch: BluetoothGattCharacteristic, name: String) {
        queue.enqueue(GattOpQueue.Op(key = ch.uuid, isRead = true, label = "read $name") {
            val gatt = gatt ?: return@Op false
            gatt.readCharacteristic(ch)
        })
    }

    private fun writeControlPoint(payload: ByteArray, label: String, quiet: Boolean = false) {
        val opcode = payload[0]
        // Fast ± taps and loop ticks would otherwise queue a full procedure each;
        // only the newest value matters.
        if (opcode == Ftms.OP_SET_TARGET_POWER || opcode == Ftms.OP_SIM_PARAMS ||
            opcode == Ftms.OP_SET_RESISTANCE)
            queue.dropPending(opcode)
        queue.enqueue(GattOpQueue.Op(key = Ftms.FTMS_CONTROL_POINT, cpOpcode = opcode, quiet = quiet,
                                     label = label, onFailed = ::ergFailed) {
            // Resolved here, not at enqueue time: the chain spans round trips, and a
            // characteristic from a previous discovery would be stale.
            val gatt = gatt ?: return@Op false
            val cp = controlPoint ?: return@Op false
            gatt.writeCharacteristic(cp, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        })
    }

    // ---- ERG entry chain ----------------------------------------------------

    private fun onCpResponse(opcode: Byte, result: Byte) {
        val ok = result == Ftms.RESULT_SUCCESS
        when (opcode) {
            Ftms.OP_REQUEST_CONTROL ->
                if (ok) {
                    haveControl = true
                    writeControlPoint(byteArrayOf(Ftms.OP_START_RESUME), "start/resume")
                    handler.removeCallbacks(ergTicker)
                    handler.postDelayed(ergTicker, ERG_TICK_MS)
                } else {
                    ergFailed("trainer refused control (0x%02x) - is another app connected?".format(result))
                }

            Ftms.OP_RESET -> {
                // A successful reset revokes control; after a refusal the state is
                // unknown. Request Control is idempotent, so re-enter the chain either
                // way - it restarts the drive loop and re-sends the current target.
                if (ok) { log("Trainer statistics reset"); haveControl = false }
                else log("Reset rejected (0x%02x) - continuing with the old session".format(result))
                writeControlPoint(byteArrayOf(Ftms.OP_REQUEST_CONTROL), "request control")
            }

            Ftms.OP_START_RESUME -> {
                // Non-fatal: plenty of trainers reject Start/Resume when already started,
                // and the target is what actually matters.
                if (!ok) log("Start/Resume rejected (0x%02x) - continuing anyway".format(result))
                if (hub.targets.mode == Mode.RESISTANCE) sendTargetResistance()
                else if (supportsPowerTarget()) sendTargetPower()
                else { simGrade = 0; sendSimGrade() }   // enter sim mode; the tick loop takes over
            }

            Ftms.OP_SET_TARGET_POWER ->
                if (ok) hub.ergStatus("ERG active - ${hub.targets.targetWatts} W")
                else ergFailed("target power rejected (0x%02x) - outside the supported range?".format(result))

            Ftms.OP_SIM_PARAMS ->
                if (!ok) ergFailed("simulation parameters rejected (0x%02x)".format(result))

            Ftms.OP_SET_RESISTANCE ->
                if (ok) hub.ergStatus("Resistance mode - level %.1f".format(hub.targets.targetResRaw / 10.0))
                else ergFailed("resistance rejected (0x%02x)".format(result))
        }
    }

    private fun sendTargetResistance(quiet: Boolean = false) {
        if (!haveControl) {
            log("Ignoring resistance: the trainer has not granted control")
            return
        }
        val r = hub.targets.targetResRaw
        val payload =
            if (RESISTANCE_AS_SINT16)
                byteArrayOf(Ftms.OP_SET_RESISTANCE, (r and 0xFF).toByte(), ((r shr 8) and 0xFF).toByte())
            else byteArrayOf(Ftms.OP_SET_RESISTANCE, r.coerceAtMost(255).toByte())
        writeControlPoint(payload, "set resistance %.1f [%s]".format(r / 10.0, payload.hex()), quiet)
    }

    private fun sendTargetPower(quiet: Boolean = false) {
        if (!haveControl) {
            log("Ignoring target power: the trainer has not granted control")
            return
        }
        val w = hub.targets.targetWatts
        // Set Target Power: opcode 0x05, sint16 little-endian watts
        writeControlPoint(
            byteArrayOf(Ftms.OP_SET_TARGET_POWER, (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte()),
            "set target power $w W", quiet
        )
    }

    // ---- ERG drive loop -----------------------------------------------------
    // Runs once a second while control is held. Trainers that support the power
    // target just get it re-sent (some only latch it while pedaling). Trainers
    // that don't get ERG *emulated*: compare the power the trainer reports against
    // the target and steer the simulated grade until they meet.

    private fun supportsPowerTarget() = targetFeatures < 0 || targetFeatures and (1 shl 3) != 0
    private fun supportsSimTarget() = targetFeatures >= 0 && targetFeatures and (1 shl 13) != 0

    private val ergTicker = object : Runnable {
        override fun run() {
            ergTick(quiet = true)
            handler.postDelayed(this, ERG_TICK_MS)
        }
    }

    /** Mode toggled in the UI - apply the new mode's target right away. */
    fun onModeChanged() {
        if (hub.targets.mode == Mode.ERG) simGrade = 0   // don't resume the sim on a stale grade
        ergTick()
    }

    /** A target changed in the UI - send it now; the tick loop keeps it refreshed. */
    fun pushTargets() = ergTick()

    /** Ride finished - reset the trainer's own session statistics (FTMS Reset, 0x01). */
    fun resetSession() {
        if (gatt == null || controlPoint == null) return
        // Reset returns the machine to idle and revokes control, so stop driving it
        // until the chain has been re-entered (see onCpResponse).
        handler.removeCallbacks(ergTicker)
        writeControlPoint(byteArrayOf(Ftms.OP_RESET), "reset trainer statistics")
    }

    private fun ergTick(quiet: Boolean = false) {
        if (!haveControl || gatt == null) return
        if (hub.targets.mode == Mode.RESISTANCE) { sendTargetResistance(quiet); return }
        if (supportsPowerTarget()) { sendTargetPower(quiet); return }
        if (!supportsSimTarget()) { ergFailed("trainer supports neither power target nor simulation"); return }
        val p = hub.lastPower
        if (p == Int.MIN_VALUE || System.currentTimeMillis() - hub.lastPowerAt > 3_000) return  // no fresh power data
        val target = hub.targets.targetWatts
        val err = target - p
        simGrade = when {
            p < 5 -> simGrade / 2          // freewheeling: unwind instead of ratcheting up
            err in -5..5 -> simGrade       // close enough; the write is just a keep-alive
            else -> (simGrade + (err * GRADE_PER_WATT).toInt()).coerceIn(gradeMin, gradeMax)
        }
        sendSimGrade(quiet)
        hub.ergStatus("ERG emulated - target $target W, riding $p W, grade %.2f%%, cmd ${queue.lastOpMs} ms"
            .format(simGrade / 100.0))
    }

    /** Indoor Bike Simulation Parameters (0x11): wind sint16 0.001 m/s, grade sint16 0.01 %,
     *  rolling resistance uint8 0.0001, wind coefficient uint8 0.01 kg/m. Only the grade varies. */
    private fun sendSimGrade(quiet: Boolean = false) {
        // simGrade accumulates unrounded so small corrections still add up; the trainer
        // gets the value floored to its reported increment.
        val g = simGrade.let { it - it.mod(gradeStep) }
        writeControlPoint(byteArrayOf(Ftms.OP_SIM_PARAMS, 0, 0,
            (g and 0xFF).toByte(), ((g shr 8) and 0xFF).toByte(), 40, 51),
            "set sim grade %.2f%%".format(g / 100.0), quiet)
    }

    // ---- GATT callback ------------------------------------------------------

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Trainer connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // A failed connect also lands here, with status 133.
                log(if (status == BluetoothGatt.GATT_SUCCESS) "Trainer disconnected"
                    else "Trainer connection lost (status=$status)")
                handler.removeCallbacks(ergTicker)
                queue.flush("trainer disconnected")
                haveControl = false
                controlPoint = null
                this@TrainerLink.gatt = null
                gatt.close()   // frees the GATT client; the stack has only ~32
                if (!closing) hub.onTrainerDown(this@TrainerLink)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ergFailed("service discovery failed (status=$status)"); return
            }
            log("Services: ${gatt.services.joinToString(", ") { uuidLabel(it.uuid) }}")
            val ftms = gatt.getService(Ftms.FTMS_SERVICE)
                ?: run { ergFailed("trainer has no FTMS service"); return }
            val bikeData = ftms.getCharacteristic(Ftms.INDOOR_BIKE_DATA)
            controlPoint = ftms.getCharacteristic(Ftms.FTMS_CONTROL_POINT)
            val statusCh = ftms.getCharacteristic(Ftms.FTMS_STATUS)
            log("FTMS service found - bike data: ${bikeData != null}, " +
                "control point: ${controlPoint != null}, status: ${statusCh != null}")
            hub.onTrainerUp(this@TrainerLink)

            bikeData?.let { enableNotifications(it) }

            // Cycling Power often notifies faster than FTMS bike data - use it for the
            // power readout and the ERG loop's feedback when available.
            val cpm = gatt.getService(Ftms.CPS_SERVICE)?.getCharacteristic(Ftms.CP_MEASUREMENT)
            if (cpm != null) {
                log("Cycling Power service found - subscribing for faster power updates")
                enableNotifications(cpm)
            } else log("No Cycling Power service - power comes from bike data only")

            statusCh?.let { enableNotifications(it) }

            // Optional informational characteristics - read once (and subscribe to
            // training status). All go through the op queue like everything else.
            ftms.getCharacteristic(Ftms.MACHINE_FEATURE)?.let { readCharacteristic(it, "machine features") }
            ftms.getCharacteristic(Ftms.SUPPORTED_POWER_RANGE)?.let { readCharacteristic(it, "power range") }
            ftms.getCharacteristic(Ftms.SUPPORTED_RESISTANCE_RANGE)?.let { readCharacteristic(it, "resistance range") }
            ftms.getCharacteristic(Ftms.SUPPORTED_INCLINE_RANGE)?.let { readCharacteristic(it, "incline range") }
            ftms.getCharacteristic(Ftms.TRAINING_STATUS)?.let {
                readCharacteristic(it, "training status")
                enableNotifications(it)
            }

            val cp = controlPoint ?: run { ergFailed("trainer has no FTMS control point"); return }
            // Responses arrive as indications; the ERG chain starts once they are on.
            enableNotifications(cp, indicate = true, onFailed = ::ergFailed)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val uuid = d.characteristic.uuid
            val failure = if (status == BluetoothGatt.GATT_SUCCESS) null
                          else "enabling ${shortUuid(uuid)} failed: ${Ftms.attError(status)}"
            if (!queue.opDone(uuid, null, failure)) return
            if (failure == null && uuid == Ftms.FTMS_CONTROL_POINT)
                writeControlPoint(byteArrayOf(Ftms.OP_REQUEST_CONTROL), "request control")
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic,
                                          value: ByteArray, status: Int) {
            val failure = if (status == BluetoothGatt.GATT_SUCCESS) null
                          else "read ${shortUuid(c.uuid)} failed: ${Ftms.attError(status)}"
            if (!queue.opDone(c.uuid, null, failure, isRead = true)) return
            if (failure == null) onReadValue(c.uuid, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid != Ftms.FTMS_CONTROL_POINT) {
                queue.opDone(c.uuid, null, if (status == BluetoothGatt.GATT_SUCCESS) null
                                           else "write to ${shortUuid(c.uuid)} failed: ${Ftms.attError(status)}")
                return
            }
            // A successful CP write only means the ATT write was accepted - the procedure
            // is not done until the 0x80 indication. On failure none is coming, so end it here.
            if (status != BluetoothGatt.GATT_SUCCESS)
                queue.abortCurrent("control point write failed: ${Ftms.attError(status)}")
            else if (!queue.currentQuiet()) log("CP write acked - awaiting 0x80 response")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                Ftms.INDOOR_BIKE_DATA -> parseIndoorBikeData(value)
                Ftms.CP_MEASUREMENT -> parseCyclingPower(value)
                Ftms.TRAINING_STATUS -> log("<- training status: ${parseTrainingStatus(value)}")
                Ftms.FTMS_STATUS -> {
                    // The sim-grade loop makes the trainer repeat "simulation parameters
                    // changed" every second - only log changes.
                    val msg = parseMachineStatus(value)
                    if (msg != lastMachineStatus) { lastMachineStatus = msg; log("<- machine status: $msg") }
                }
                Ftms.FTMS_CONTROL_POINT -> {
                    // 0x80 | requestOp | result (0x01 = success)
                    if (value.size < 3 || value[0] != Ftms.OP_RESPONSE) {
                        log("<- malformed CP indication ${value.hex()} - ignored"); return
                    }
                    val opcode = value[1]
                    val result = value[2]
                    if (!queue.currentQuiet() || result != Ftms.RESULT_SUCCESS)
                        log("<- CP response op=0x%02x result=0x%02x".format(opcode, result))
                    // Only advance the chain if this answers the procedure we are awaiting.
                    if (!queue.opDone(Ftms.FTMS_CONTROL_POINT, opcode, null)) return
                    onCpResponse(opcode, result)
                }
            }
        }
    }

    // ---- Parsers ------------------------------------------------------------

    /** FTMS Indoor Bike Data (0x2AD2): flags uint16, then optional fields in order. */
    private fun parseIndoorBikeData(v: ByteArray) {
        var i = 0
        fun u8() = (v[i].toInt() and 0xFF).also { i += 1 }
        fun u16() = ((v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)).also { i += 2 }
        fun s16() = u16().toShort().toInt()
        fun u24() = u16() or (u8() shl 16)

        var speed = -1.0; var cadence = -1.0; var power = Int.MIN_VALUE; var heartRate = -1
        val fields = mutableListOf<String>()
        try {
            val flags = u16()
            if (flags and 0x0001 == 0) { speed = u16() / 100.0; fields += "speed %.2f km/h".format(speed) }
            if (flags and 0x0002 != 0) fields += "avg speed %.2f km/h".format(u16() / 100.0)
            if (flags and 0x0004 != 0) { cadence = u16() / 2.0; fields += "cadence %.0f rpm".format(cadence) }
            if (flags and 0x0008 != 0) fields += "avg cadence %.1f rpm".format(u16() / 2.0)
            if (flags and 0x0010 != 0) fields += "distance ${u24()} m"
            if (flags and 0x0020 != 0) fields += "resistance ${s16()}"
            if (flags and 0x0040 != 0) { power = s16(); fields += "power $power W" }
            if (flags and 0x0080 != 0) fields += "avg power ${s16()} W"
            if (flags and 0x0100 != 0) fields += "energy ${u16()} kcal (${u16()}/h, ${u8()}/min)"
            if (flags and 0x0200 != 0) { heartRate = u8(); fields += "HR $heartRate bpm" }
            if (flags and 0x0400 != 0) fields += "MET %.1f".format(u8() / 10.0)
            if (flags and 0x0800 != 0) fields += "elapsed ${u16()} s"
            if (flags and 0x1000 != 0) fields += "remaining ${u16()} s"
        } catch (e: IndexOutOfBoundsException) {
            log("Short Indoor Bike Data packet (${v.size} B) - ignored")
            return
        }

        val now = System.currentTimeMillis()
        if (speed >= 0) { hub.lastSpeedKmh = speed; hub.lastSpeedAt = now }
        if (cadence >= 0) { hub.lastCadence = cadence; hub.lastCadenceAt = now }
        if (power != Int.MIN_VALUE) { hub.lastPower = power; hub.lastPowerAt = now }
        // Trainer-relayed HR is a fallback only: a connected strap owns lastHr. Gating on
        // link state (not sample freshness) keeps the two sources from interleaving.
        if (heartRate > 0 && hub.hrState != BleCentral.LinkState.CONNECTED) {
            hub.lastHr = heartRate; hub.lastHrAt = now
        }

        // Several packets per second would drown everything else in the log;
        // a periodic full dump - with the actual arrival rate - is enough.
        bikeDataCount++
        if (now - lastBikeDataLog >= BIKE_DATA_LOG_MS) {
            val hz = bikeDataCount * 1000.0 / (now - lastBikeDataLog).coerceAtMost(60_000)
            if (lastBikeDataLog != 0L) fields += "(%.1f Hz)".format(hz)
            lastBikeDataLog = now
            bikeDataCount = 0
            log("<- bike data: ${fields.joinToString(", ")}")
        }

        hub.onLiveChanged()
    }

    /** Cycling Power Measurement (0x2A63): flags uint16, instantaneous power sint16, then
     *  optional fields we don't need. Feeds the ERG loop. */
    private fun parseCyclingPower(v: ByteArray) {
        if (v.size < 4) { log("Short Cycling Power packet (${v.size} B) - ignored"); return }
        val p = v.s16(2)
        val now = System.currentTimeMillis()
        hub.lastPower = p
        hub.lastPowerAt = now
        cpmCount++
        if (cpmWindowStart == 0L) { cpmWindowStart = now; hub.onLiveChanged(); return }
        if (now - cpmWindowStart >= BIKE_DATA_LOG_MS) {
            log("<- cycling power: $p W (%.1f Hz)".format(cpmCount * 1000.0 / (now - cpmWindowStart)))
            cpmCount = 0; cpmWindowStart = now
        }
        hub.onLiveChanged()
    }

    /** Pretty-prints the one-shot reads; ranges feed the UI's clamping. */
    private fun onReadValue(uuid: UUID, v: ByteArray) {
        when (uuid) {
            Ftms.MACHINE_FEATURE ->
                if (v.size < 8) log("<- machine features: short packet ${v.hex()}")
                else {
                    // Two uint32 bit fields: machine features, then target setting features.
                    val targets = v.u16(4) or (v.u16(6) shl 16)
                    targetFeatures = targets
                    log("<- machine features: ${Ftms.bitNames(v.u16(0) or (v.u16(2) shl 16), Ftms.MACHINE_FEATURE_NAMES)}")
                    log("<- supported targets: ${Ftms.bitNames(targets, Ftms.TARGET_FEATURE_NAMES)}")
                    if (targets and (1 shl 3) == 0)
                        log(if (targets and (1 shl 13) != 0)
                                "Power target unsupported - ERG will be emulated via bike-simulation grade"
                            else "Power target unsupported and no simulation either - ERG is not possible")
                }
            Ftms.SUPPORTED_POWER_RANGE ->
                if (v.size < 6) log("<- power range: short packet ${v.hex()}")
                else {
                    hub.minPower = v.s16(0); hub.maxPower = v.s16(2)
                    hub.powerStep = v.u16(4).coerceAtLeast(1)
                    log("<- power range: ${hub.minPower}-${hub.maxPower} W in ${hub.powerStep} W steps - ERG target now clamps to this")
                    hub.onRangesChanged()
                }
            Ftms.SUPPORTED_RESISTANCE_RANGE ->
                if (v.size < 6) log("<- resistance range: short packet ${v.hex()}")
                else {
                    hub.resMin = v.s16(0); hub.resMax = v.s16(2)
                    hub.resStep = v.u16(4).coerceAtLeast(1)
                    log("<- resistance range: %.1f to %.1f in %.1f steps - resistance mode clamps to this"
                        .format(hub.resMin / 10.0, hub.resMax / 10.0, hub.resStep / 10.0))
                    hub.onRangesChanged()
                }
            Ftms.SUPPORTED_INCLINE_RANGE ->
                if (v.size < 6) log("<- incline range: short packet ${v.hex()}")
                else {
                    // Characteristic is in 0.1 % units, the sim-grade loop works in 0.01 %.
                    gradeMin = v.s16(0) * 10; gradeMax = v.s16(2) * 10
                    gradeStep = (v.u16(4) * 10).coerceAtLeast(1)
                    log("<- incline range: %.1f%% to %.1f%% in %.1f%% steps - sim grade now clamps to this"
                        .format(gradeMin / 100.0, gradeMax / 100.0, gradeStep / 100.0))
                    simGrade = simGrade.coerceIn(gradeMin, gradeMax)
                }
            Ftms.TRAINING_STATUS -> log("<- training status: ${parseTrainingStatus(v)}")
            else -> log("<- read ${shortUuid(uuid)}: ${v.hex()}")
        }
    }

    /** Training Status (0x2AD3): flags uint8, status uint8, optional string. */
    private fun parseTrainingStatus(v: ByteArray): String {
        if (v.size < 2) return "malformed ${v.hex()}"
        val names = arrayOf(
            "other", "idle", "warming up", "low intensity interval", "high intensity interval",
            "recovery interval", "isometric", "heart rate control", "fitness test",
            "speed below control region", "speed above control region", "cool down",
            "watt control", "manual mode", "pre-workout", "post-workout")
        val status = v[1].toInt() and 0xFF
        val base = names.getOrNull(status) ?: "unknown 0x%02x".format(status)
        return if (v[0].toInt() and 0x01 != 0 && v.size > 2)
            "$base \"${v.copyOfRange(2, v.size).decodeToString()}\"" else base
    }

    /** Fitness Machine Status (0x2ADA): opcode uint8, opcode-specific parameters. */
    private fun parseMachineStatus(v: ByteArray): String {
        if (v.isEmpty()) return "(empty)"
        return try {
            when (v[0].toInt() and 0xFF) {
                0x01 -> "reset"
                0x02 -> if (v[1].toInt() == 0x02) "paused by user" else "stopped by user"
                0x03 -> "stopped by safety key"
                0x04 -> "started/resumed by user"
                0x05 -> "target speed changed to %.2f km/h".format(v.u16(1) / 100.0)
                0x06 -> "target incline changed to %.1f%%".format(v.s16(1) / 10.0)
                // Same uint8-vs-sint16 spec ambiguity as the Set command - accept both.
                0x07 -> "target resistance changed to %.1f".format(
                    (if (v.size >= 3) v.s16(1) else v[1].toInt() and 0xFF) / 10.0)
                0x08 -> "target power changed to ${v.s16(1)} W"
                0x09 -> "target heart rate changed to ${v[1].toInt() and 0xFF} bpm"
                0x12 -> "bike simulation parameters changed"
                0x14 -> "spin down status ${v[1].toInt() and 0xFF}"
                0x15 -> "target cadence changed to %.1f rpm".format(v.u16(1) / 2.0)
                0xFF -> "control permission lost"
                else -> "op 0x%02x data [%s]".format(v[0], v.copyOfRange(1, v.size).hex())
            }
        } catch (e: IndexOutOfBoundsException) {
            "malformed ${v.hex()}"
        }
    }
}

package com.example.ergpoc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Minimal proof-of-concept:
 *  - Connects to an FTMS bike trainer/ergometer (Hammer Varon XTR II exposes the
 *    standard Bluetooth Fitness Machine Service) and a Polar H10 (standard Heart
 *    Rate Service).
 *  - ERG mode: writes "Set Target Power" to the FTMS Control Point. The machine's
 *    firmware then adjusts resistance itself to hold the wattage regardless of cadence.
 *
 * Everything is in one file on purpose. No architecture, no reconnect logic - it is
 * a PoC. It does handle GATT failures, because silently doing nothing is worse than
 * not handling them.
 */
@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    // ---- UUIDs -------------------------------------------------------------
    private val FTMS_SERVICE        = uuid16(0x1826)
    private val MACHINE_FEATURE     = uuid16(0x2ACC)   // read: capability bits
    private val INDOOR_BIKE_DATA    = uuid16(0x2AD2)   // notify: speed/cadence/power
    private val TRAINING_STATUS     = uuid16(0x2AD3)   // read + notify: workout state
    private val SUPPORTED_INCLINE_RANGE    = uuid16(0x2AD5)   // read
    private val SUPPORTED_RESISTANCE_RANGE = uuid16(0x2AD6)   // read
    private val SUPPORTED_POWER_RANGE      = uuid16(0x2AD8)   // read
    private val FTMS_CONTROL_POINT  = uuid16(0x2AD9)   // write + indicate: ERG commands
    private val FTMS_STATUS         = uuid16(0x2ADA)   // notify (optional)

    private val CPS_SERVICE         = uuid16(0x1818)   // Cycling Power - often notifies faster than FTMS
    private val CP_MEASUREMENT      = uuid16(0x2A63)

    private val HR_SERVICE          = uuid16(0x180D)
    private val HR_MEASUREMENT      = uuid16(0x2A37)

    private val CCCD                = uuid16(0x2902)

    private fun uuid16(v: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

    /** "0x2ad9" - for log lines. */
    private fun short(u: UUID) = "0x%04x".format((u.mostSignificantBits shr 32).toInt() and 0xFFFF)

    /** Short form for SIG-assigned UUIDs, full form for vendor ones. */
    private fun uuidLabel(u: UUID) =
        if (u.toString().endsWith("-0000-1000-8000-00805f9b34fb")) short(u) else u.toString()

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    /** Little-endian field access for fixed-layout characteristics. */
    private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.s16(i: Int) = u16(i).toShort().toInt()

    // ---- FTMS Control Point opcodes ---------------------------------------
    private val OP_REQUEST_CONTROL: Byte = 0x00
    private val OP_SET_RESISTANCE: Byte = 0x04
    private val OP_SET_TARGET_POWER: Byte = 0x05
    private val OP_START_RESUME: Byte = 0x07
    private val OP_SIM_PARAMS: Byte = 0x11
    private val OP_RESPONSE: Byte = 0x80.toByte()
    private val RESULT_SUCCESS: Byte = 0x01

    /** Android allows ~30s for an ATT transaction; sit under that but above any real procedure. */
    private val OP_TIMEOUT_MS = 15_000L
    private val SCAN_TIMEOUT_MS = 20_000L

    /** Bike-data notifications arrive several times a second; log the full parse this often. */
    private val BIKE_DATA_LOG_MS = 2_000L
    // Only touched from the trainer's GATT callback thread.
    private var lastBikeDataLog = 0L
    private var bikeDataCount = 0
    private var cpmCount = 0
    private var cpmWindowStart = 0L

    private val requiredPerms =
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    // ---- State -------------------------------------------------------------
    // Written from GATT callbacks (binder threads), read from the UI thread.
    @Volatile private var trainerGatt: BluetoothGatt? = null
    @Volatile private var hrGatt: BluetoothGatt? = null
    @Volatile private var controlPoint: BluetoothGattCharacteristic? = null
    @Volatile private var haveControl = false
    @Volatile private var targetWatts = 100
    @Volatile private var monitorOnly = false      // connected as a display only; never take control

    private enum class Mode { ERG, RESISTANCE }
    @Volatile private var mode = Mode.ERG
    // Resistance target in the characteristic's raw 0.1 unitless units.
    @Volatile private var targetResRaw = 0
    // Defaults until the Supported Resistance Level Range read arrives (raw 0.1 units).
    @Volatile private var resMin = 0
    @Volatile private var resMax = 1_000
    @Volatile private var resStep = 10
    // Defaults until the Supported Power Range read arrives.
    @Volatile private var minPower = 25
    @Volatile private var maxPower = 500
    @Volatile private var powerStep = 1
    // ERG emulation state (for trainers whose feature read lacks the power target):
    @Volatile private var targetFeatures = -1      // Target Setting Features bits; -1 until read
    @Volatile private var lastPower = Int.MIN_VALUE
    @Volatile private var lastPowerAt = 0L
    @Volatile private var simGrade = 0             // Indoor Bike Simulation grade, 0.01 % units
    @Volatile private var lastMachineStatus = ""
    // Grade limits in 0.01 % units; defaults until the Supported Inclination Range read arrives.
    @Volatile private var gradeMin = -1_000        // -10 %
    @Volatile private var gradeMax = 3_000         // +30 %
    @Volatile private var gradeStep = 1
    @Volatile private var lastOpMs = 0L            // duration of the last completed GATT op
    @Volatile private var scanning = false

    private val handler = Handler(Looper.getMainLooper())

    // ---- Trivial UI --------------------------------------------------------
    private lateinit var statusView: TextView
    private lateinit var powerView: TextView
    private lateinit var targetView: TextView
    private lateinit var hrView: TextView
    private lateinit var logView: TextView
    private lateinit var stepButtons: List<Button>
    private val ergDeltas = intArrayOf(-25, -5, +5, +25)   // watts
    private val resDeltas = intArrayOf(-5, -1, +1, +5)     // resistance levels

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) setStatus("Ready - tap Scan + connect")
            else setStatus("Bluetooth permission denied - scanning is not possible")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            // targetSdk 35+ forces edge-to-edge: without consuming the insets the
            // status bar draws over the top of the layout.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(40, bars.top + 20, 40, bars.bottom + 20)
                insets
            }
        }
        statusView = TextView(this).apply { textSize = 16f; text = "Idle" }
        powerView  = TextView(this).apply { textSize = 40f; text = "-- W   -- rpm" }
        hrView     = TextView(this).apply { textSize = 40f; text = "-- bpm" }
        targetView = TextView(this).apply { textSize = 24f; gravity = Gravity.CENTER }
        logView    = TextView(this).apply { textSize = 11f }
        updateTargetLabel()

        fun scanBtn(label: String, monitor: Boolean) = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startScan(monitor) }
        }
        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanRow.addView(scanBtn("Scan + ERG", false))
        scanRow.addView(scanBtn("Scan, monitor only", true))

        val modeBtn = Button(this).apply {
            text = "Mode: ERG"
            setOnClickListener {
                mode = if (mode == Mode.ERG) Mode.RESISTANCE else Mode.ERG
                text = if (mode == Mode.ERG) "Mode: ERG" else "Mode: Resistance"
                if (mode == Mode.ERG) simGrade = 0   // don't resume the sim on a stale grade
                updateStepButtons()
                updateTargetLabel()
                ergTick()   // apply the new mode's target right away when connected
            }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stepButtons = List(4) { i ->
            Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (monitorOnly) {
                        setStatus("Monitor only - set the target on the trainer's console")
                        return@setOnClickListener
                    }
                    if (mode == Mode.ERG) targetWatts = clampTarget(targetWatts + ergDeltas[i])
                    else targetResRaw = clampRes(targetResRaw + resDeltas[i] * 10)
                    updateTargetLabel()
                    ergTick()   // sends the new target now; the tick loop keeps it refreshed
                }
                row.addView(this)
            }
        }
        updateStepButtons()

        root.addView(scanRow)
        root.addView(statusView)
        root.addView(powerView)
        root.addView(hrView)
        root.addView(targetView)
        root.addView(modeBtn)
        root.addView(row)
        // Weighted height: the log fills whatever is left and scrolls instead of
        // pushing past the bottom edge.
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (!hasPermissions()) permissionLauncher.launch(requiredPerms)
    }

    private fun updateTargetLabel() = runOnUiThread {
        targetView.text = if (mode == Mode.ERG) "ERG target: $targetWatts W"
                          else "Resistance target: %.1f".format(targetResRaw / 10.0)
    }

    private fun updateStepButtons() = runOnUiThread {
        val deltas = if (mode == Mode.ERG) ergDeltas else resDeltas
        stepButtons.forEachIndexed { i, b -> b.text = "%+d".format(deltas[i]) }
    }

    private fun setStatus(s: String) = runOnUiThread { statusView.text = s }

    private fun log(msg: String) {
        Log.d("ErgPoc", msg)
        runOnUiThread { logView.text = "$msg\n${logView.text}".take(4000) }
    }

    /** ERG entry gave up. Surfaced in the UI - the log alone is too easy to miss. */
    private fun ergFailed(reason: String) = setStatus("ERG unavailable: $reason")

    // ---- Permissions --------------------------------------------------------
    private fun hasPermissions() = requiredPerms.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---- Scanning -----------------------------------------------------------
    // Advertisers repeat several times a second; log each device once per scan.
    private val seenDevices = HashSet<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            val dev = result.device
            if (seenDevices.add(dev.address))
                log("Found ${dev.name ?: "(unnamed)"} [${dev.address}] rssi=${result.rssi} dBm " +
                    "services=${uuids.joinToString(",") { short(it.uuid) }}")
            if (uuids.contains(ParcelUuid(FTMS_SERVICE)) && trainerGatt == null) {
                log("Connecting to trainer ${dev.name ?: dev.address}…")
                trainerGatt = dev.connectGatt(this@MainActivity, false, trainerCb, BluetoothDevice.TRANSPORT_LE)
            } else if (uuids.contains(ParcelUuid(HR_SERVICE)) && hrGatt == null &&
                       dev.address != trainerGatt?.device?.address) {
                // else-if + address check: some trainers relay HR, and connecting to the
                // same device twice would burn a second GATT client and strand the H10.
                log("Connecting to HR sensor ${dev.name ?: dev.address}…")
                hrGatt = dev.connectGatt(this@MainActivity, false, hrCb, BluetoothDevice.TRANSPORT_LE)
            }
            if (trainerGatt != null && hrGatt != null) stopScan("both devices found")
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Scan failed errorCode=$errorCode")
            setStatus("Scan failed ($errorCode)")
        }
    }

    private val scanTimeoutRunnable = Runnable {
        stopScan("timed out")
        val missing = listOfNotNull(
            if (trainerGatt == null) "trainer" else null,
            if (hrGatt == null) "HR strap" else null
        )
        if (missing.isNotEmpty()) setStatus("Scan timed out - no ${missing.joinToString(" or ")} found")
    }

    /** Null when there is no adapter or Bluetooth is turned off. */
    private fun scanner(): BluetoothLeScanner? =
        getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    private fun startScan(monitor: Boolean = false) {
        if (!hasPermissions()) {
            setStatus("Bluetooth permissions required")
            permissionLauncher.launch(requiredPerms)
            return
        }
        val scanner = scanner() ?: run {
            setStatus("Bluetooth is off")
            log("No BLE scanner available - turn Bluetooth on and try again.")
            return
        }
        if (scanning) return
        scanning = true
        monitorOnly = monitor
        seenDevices.clear()
        setStatus(if (monitor) "Scanning… (monitor only)" else "Scanning…")
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(FTMS_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)
        log("Scan started - filtering for FTMS ${short(FTMS_SERVICE)} and HR ${short(HR_SERVICE)}, " +
            "${SCAN_TIMEOUT_MS / 1000}s timeout")
        // Android silently returns no results after 5 scan starts in 30s, so an
        // unstoppable scan is not harmless.
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    private fun stopScan(why: String) {
        handler.removeCallbacks(scanTimeoutRunnable)
        if (!scanning) return
        scanning = false
        scanner()?.stopScan(scanCallback)
        log("Scan stopped ($why)")
        if (trainerGatt != null || hrGatt != null) setStatus("Connecting…")
    }

    // ---- Trainer GATT op queue ---------------------------------------------
    // Android allows one in-flight op per *connection*, so only the trainer needs a
    // queue; the H10 issues a single descriptor write and nothing else.
    //
    // An op is identified by the characteristic it targets, plus - for control point
    // writes - the opcode whose 0x80 response completes it. Completions are matched
    // against the in-flight op: after a timeout a late callback would otherwise
    // complete whatever op happened to be running next, permanently desynchronising
    // the queue.

    private class Op(
        val key: UUID,
        val cpOpcode: Byte? = null,      // non-null: completes on the 0x80 response to this opcode
        val isRead: Boolean = false,     // a read and a CCCD write on the same characteristic are distinct ops
        val quiet: Boolean = false,      // routine refresh op: logged only on failure
        val label: String,
        val onFailed: (String) -> Unit = {},   // must not enqueue
        val start: () -> Boolean               // false = never started, no callback is coming
    ) {
        var startedAt = 0L
    }

    private val ops = ConcurrentLinkedQueue<Op>()
    private var current: Op? = null
    private var opGeneration = 0
    private var opTimeout: Runnable? = null

    private fun enqueue(op: Op) { ops.add(op); driveQueue() }

    @Synchronized private fun driveQueue() {
        if (current != null) return
        while (true) {
            val op = ops.poll() ?: return
            current = op
            if (!op.quiet) log("-> ${op.label}")
            op.startedAt = System.currentTimeMillis()
            // A throw here would strand the queue with no timeout armed - the one
            // state the watchdog below cannot rescue. Treat it as a failed start.
            val started = try { op.start() } catch (t: Throwable) { log("${op.label}: $t"); false }
            if (started) {
                val gen = ++opGeneration
                val r = Runnable { onOpTimeout(gen) }
                opTimeout = r
                handler.postDelayed(r, OP_TIMEOUT_MS)
                return
            }
            current = null
            val why = "${op.label}: write did not start"
            log(why); op.onFailed(why)
        }
    }

    /** Completes the in-flight op if [key]/[cpOpcode]/[isRead] identify it. Returns false for strays. */
    @Synchronized private fun opDone(key: UUID, cpOpcode: Byte?, failure: String?,
                                     isRead: Boolean = false): Boolean {
        val op = current
        if (op == null || op.key != key || op.cpOpcode != cpOpcode || op.isRead != isRead) {
            log("Stray GATT completion for ${short(key)} - ignored")
            return false
        }
        finish(op, failure)
        return true
    }

    /** Whether the in-flight op is a quiet refresh - used to mute its routine log lines. */
    @Synchronized private fun currentQuiet() = current?.quiet == true

    /** Completes the in-flight op regardless of identity (timeout, disconnect). */
    @Synchronized private fun abortCurrent(reason: String) {
        finish(current ?: return, reason)
    }

    private fun finish(op: Op, failure: String?) {
        opTimeout?.let { handler.removeCallbacks(it) }
        opTimeout = null
        opGeneration++
        current = null
        val ms = System.currentTimeMillis() - op.startedAt
        lastOpMs = ms
        if (failure != null) { log("$failure (after $ms ms)"); op.onFailed(failure) }
        else if (!op.quiet) log("<- ${op.label}: done ($ms ms)")
        driveQueue()
    }

    @Synchronized private fun onOpTimeout(gen: Int) {
        if (gen != opGeneration) return
        abortCurrent("${current?.label ?: "GATT op"}: no reply in ${OP_TIMEOUT_MS}ms")
    }

    @Synchronized private fun flushQueue(reason: String) {
        ops.clear()
        abortCurrent(reason)
    }

    private fun enableNotifications(ch: BluetoothGattCharacteristic, indicate: Boolean = false,
                                    onFailed: (String) -> Unit = {}) {
        val kind = if (indicate) "indications" else "notifications"
        enqueue(Op(key = ch.uuid, label = "enable $kind on ${short(ch.uuid)}", onFailed = onFailed) {
            val gatt = trainerGatt ?: return@Op false
            val cccd = ch.getDescriptor(CCCD) ?: return@Op false
            val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            // Without setCharacteristicNotification the CCCD write still succeeds but
            // Android never dispatches the callbacks locally - the op would "time out".
            gatt.setCharacteristicNotification(ch, true) &&
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        })
    }

    /** One-shot informational read; the value is parsed and logged in onCharacteristicRead. */
    private fun readCharacteristic(ch: BluetoothGattCharacteristic, name: String) {
        enqueue(Op(key = ch.uuid, isRead = true, label = "read $name") {
            val gatt = trainerGatt ?: return@Op false
            gatt.readCharacteristic(ch)
        })
    }

    private fun writeControlPoint(payload: ByteArray, label: String, quiet: Boolean = false) {
        val opcode = payload[0]
        // Fast ± taps and loop ticks would otherwise queue a full procedure each;
        // only the newest value matters.
        if (opcode == OP_SET_TARGET_POWER || opcode == OP_SIM_PARAMS || opcode == OP_SET_RESISTANCE)
            ops.removeAll { it.cpOpcode == opcode }
        enqueue(Op(key = FTMS_CONTROL_POINT, cpOpcode = opcode, quiet = quiet, label = label,
                   onFailed = ::ergFailed) {
            // Resolved here, not at enqueue time: the chain spans round trips, and a
            // characteristic from a previous discovery would be stale.
            val gatt = trainerGatt ?: return@Op false
            val cp = controlPoint ?: return@Op false
            gatt.writeCharacteristic(cp, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        })
    }

    // ---- ERG entry ----------------------------------------------------------
    // FTMS treats each control point write as a procedure that is not complete until
    // its 0x80 response indication arrives, so the sequence is chained off the
    // responses rather than written back-to-back:
    //   CP CCCD enabled -> 0x00 Request Control -> 0x07 Start/Resume ->
    //   0x05 Set Target Power (native ERG) or 0x11 sim params (emulated ERG)

    private fun onCpResponse(opcode: Byte, result: Byte) {
        val ok = result == RESULT_SUCCESS
        when (opcode) {
            OP_REQUEST_CONTROL ->
                if (ok) {
                    haveControl = true
                    writeControlPoint(byteArrayOf(OP_START_RESUME), "start/resume")
                    handler.removeCallbacks(ergTicker)
                    handler.postDelayed(ergTicker, ERG_TICK_MS)
                } else {
                    ergFailed("trainer refused control (0x%02x) - is another app connected?".format(result))
                }

            OP_START_RESUME -> {
                // Non-fatal: plenty of trainers reject Start/Resume when already started,
                // and the target is what actually matters.
                if (!ok) log("Start/Resume rejected (0x%02x) - continuing anyway".format(result))
                if (mode == Mode.RESISTANCE) sendTargetResistance()
                else if (supportsPowerTarget()) sendTargetPower()
                else { simGrade = 0; sendSimGrade() }   // enter sim mode; the tick loop takes over
            }

            OP_SET_TARGET_POWER ->
                if (ok) setStatus("ERG active - $targetWatts W")
                else ergFailed("target power rejected (0x%02x) - outside the supported range?".format(result))

            OP_SIM_PARAMS ->
                if (!ok) ergFailed("simulation parameters rejected (0x%02x)".format(result))

            OP_SET_RESISTANCE ->
                if (ok) setStatus("Resistance mode - level %.1f".format(targetResRaw / 10.0))
                else ergFailed("resistance rejected (0x%02x)".format(result))
        }
    }

    /** Clamps to the trainer's reported power range, rounded down to its increment. */
    private fun clampTarget(w: Int): Int {
        val stepped = if (w > minPower) minPower + (w - minPower) / powerStep * powerStep else w
        return stepped.coerceIn(minPower, maxPower)
    }

    /** Clamps to the trainer's reported resistance range, rounded down to its increment. */
    private fun clampRes(raw: Int): Int {
        val c = raw.coerceIn(resMin, resMax)
        return resMin + (c - resMin) / resStep * resStep
    }

    // The FTMS spec is inconsistent here: the range characteristic is sint16 but the Set
    // Target Resistance Level parameter is spec'd as a single uint8 (both 0.1 units).
    // Firmwares implement either. Flip this if the trainer ignores resistance commands.
    private val RESISTANCE_AS_SINT16 = false

    private fun sendTargetResistance(quiet: Boolean = false) {
        if (!haveControl) {
            log("Ignoring resistance: the trainer has not granted control")
            return
        }
        val r = targetResRaw
        val payload =
            if (RESISTANCE_AS_SINT16)
                byteArrayOf(OP_SET_RESISTANCE, (r and 0xFF).toByte(), ((r shr 8) and 0xFF).toByte())
            else byteArrayOf(OP_SET_RESISTANCE, r.coerceAtMost(255).toByte())
        writeControlPoint(payload, "set resistance %.1f [%s]".format(r / 10.0, payload.hex()), quiet)
    }

    private fun sendTargetPower(quiet: Boolean = false) {
        if (!haveControl) {
            log("Ignoring target power: the trainer has not granted control")
            return
        }
        val w = targetWatts
        // Set Target Power: opcode 0x05, sint16 little-endian watts
        writeControlPoint(
            byteArrayOf(OP_SET_TARGET_POWER, (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte()),
            "set target power $w W", quiet
        )
    }

    // ---- ERG drive loop -----------------------------------------------------
    // Runs once a second while control is held. Trainers that support the power
    // target just get it re-sent (some only latch it while pedaling). Trainers
    // that don't (this one's feature read admits only "resistance" and "bike
    // simulation" targets - it acks Set Target Power but never applies it) get
    // ERG *emulated*: compare the power the trainer reports against the target
    // and steer the simulated grade until they meet.

    private val ERG_TICK_MS = 1_000L
    private val GRADE_PER_WATT = 0.5   // P gain; raise for faster convergence, lower if it oscillates

    private fun supportsPowerTarget() = targetFeatures < 0 || targetFeatures and (1 shl 3) != 0
    private fun supportsSimTarget()   = targetFeatures >= 0 && targetFeatures and (1 shl 13) != 0

    private val ergTicker = object : Runnable {
        override fun run() {
            ergTick(quiet = true)
            handler.postDelayed(this, ERG_TICK_MS)
        }
    }

    private fun ergTick(quiet: Boolean = false) {
        if (!haveControl || trainerGatt == null) return
        if (mode == Mode.RESISTANCE) { sendTargetResistance(quiet); return }
        if (supportsPowerTarget()) { sendTargetPower(quiet); return }
        if (!supportsSimTarget()) { ergFailed("trainer supports neither power target nor simulation"); return }
        val p = lastPower
        if (p == Int.MIN_VALUE || System.currentTimeMillis() - lastPowerAt > 3_000) return  // no fresh power data
        val err = targetWatts - p
        simGrade = when {
            p < 5 -> simGrade / 2          // freewheeling: unwind instead of ratcheting up
            err in -5..5 -> simGrade       // close enough; the write is just a keep-alive
            else -> (simGrade + (err * GRADE_PER_WATT).toInt()).coerceIn(gradeMin, gradeMax)
        }
        sendSimGrade(quiet)
        setStatus("ERG emulated - target $targetWatts W, riding $p W, grade %.2f%%, cmd $lastOpMs ms"
            .format(simGrade / 100.0))
    }

    /** Indoor Bike Simulation Parameters (0x11): wind sint16 0.001 m/s, grade sint16 0.01 %,
     *  rolling resistance uint8 0.0001, wind coefficient uint8 0.01 kg/m. Only the grade varies. */
    private fun sendSimGrade(quiet: Boolean = false) {
        // simGrade accumulates unrounded so small corrections still add up; the trainer
        // gets the value floored to its reported increment.
        val g = simGrade.let { it - it.mod(gradeStep) }
        writeControlPoint(byteArrayOf(OP_SIM_PARAMS, 0, 0,
            (g and 0xFF).toByte(), ((g shr 8) and 0xFF).toByte(), 40, 51),
            "set sim grade %.2f%%".format(g / 100.0), quiet)
    }

    /** ATT error codes the FTMS control point uses; 128 is the classic pipelining symptom. */
    private fun attError(status: Int) = when (status) {
        128 -> "0x80 control point procedure already in progress"
        129 -> "0x81 CCCD improperly configured"
        else -> "status=$status"
    }

    // ---- Trainer GATT callback ---------------------------------------------
    private val trainerCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Trainer connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // A failed connect also lands here, with status 133.
                log(if (status == BluetoothGatt.GATT_SUCCESS) "Trainer disconnected"
                    else "Trainer connection lost (status=$status)")
                setStatus("Trainer disconnected")
                handler.removeCallbacks(ergTicker)
                flushQueue("trainer disconnected")
                haveControl = false
                controlPoint = null
                trainerGatt = null
                gatt.close()   // frees the GATT client; the stack has only ~32
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ergFailed("service discovery failed (status=$status)"); return
            }
            log("Services: ${gatt.services.joinToString(", ") { uuidLabel(it.uuid) }}")
            val ftms = gatt.getService(FTMS_SERVICE) ?: run { ergFailed("trainer has no FTMS service"); return }
            val bikeData = ftms.getCharacteristic(INDOOR_BIKE_DATA)
            controlPoint = ftms.getCharacteristic(FTMS_CONTROL_POINT)
            val statusCh = ftms.getCharacteristic(FTMS_STATUS)
            log("FTMS service found - bike data: ${bikeData != null}, " +
                "control point: ${controlPoint != null}, status: ${statusCh != null}")

            bikeData?.let { enableNotifications(it) }

            // Cycling Power often notifies faster than FTMS bike data - use it for the
            // power readout and the ERG loop's feedback when available.
            val cpm = gatt.getService(CPS_SERVICE)?.getCharacteristic(CP_MEASUREMENT)
            if (cpm != null) {
                log("Cycling Power service found - subscribing for faster power updates")
                enableNotifications(cpm)
            } else log("No Cycling Power service - power comes from bike data only")

            if (monitorOnly) {
                // Measurements only (watts + cadence). No status subscriptions, no
                // informational reads, and above all no control point - the trainer's
                // own console keeps control.
                log("Monitor mode - measurements only, not requesting control")
                setStatus("Monitor only - control from the trainer's console")
                return
            }

            statusCh?.let { enableNotifications(it) }

            // Optional informational characteristics - read once (and subscribe to
            // training status), log only. All go through the op queue like everything else.
            ftms.getCharacteristic(MACHINE_FEATURE)?.let { readCharacteristic(it, "machine features") }
            ftms.getCharacteristic(SUPPORTED_POWER_RANGE)?.let { readCharacteristic(it, "power range") }
            ftms.getCharacteristic(SUPPORTED_RESISTANCE_RANGE)?.let { readCharacteristic(it, "resistance range") }
            ftms.getCharacteristic(SUPPORTED_INCLINE_RANGE)?.let { readCharacteristic(it, "incline range") }
            ftms.getCharacteristic(TRAINING_STATUS)?.let {
                readCharacteristic(it, "training status")
                enableNotifications(it)
            }

            val cp = controlPoint ?: run { ergFailed("trainer has no FTMS control point"); return }
            // Responses arrive as indications; the ERG chain starts once they are on.
            enableNotifications(cp, indicate = true, onFailed = ::ergFailed)
            setStatus("Trainer ready, requesting control…")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val uuid = d.characteristic.uuid
            val failure = if (status == BluetoothGatt.GATT_SUCCESS) null
                          else "enabling ${short(uuid)} failed: ${attError(status)}"
            if (!opDone(uuid, null, failure)) return
            if (failure == null && uuid == FTMS_CONTROL_POINT)
                writeControlPoint(byteArrayOf(OP_REQUEST_CONTROL), "request control")
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic,
                                          value: ByteArray, status: Int) {
            val failure = if (status == BluetoothGatt.GATT_SUCCESS) null
                          else "read ${short(c.uuid)} failed: ${attError(status)}"
            if (!opDone(c.uuid, null, failure, isRead = true)) return
            if (failure == null) logReadValue(c.uuid, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid != FTMS_CONTROL_POINT) {
                opDone(c.uuid, null, if (status == BluetoothGatt.GATT_SUCCESS) null
                                     else "write to ${short(c.uuid)} failed: ${attError(status)}")
                return
            }
            // A successful CP write only means the ATT write was accepted - the procedure
            // is not done until the 0x80 indication. On failure none is coming, so end it here.
            if (status != BluetoothGatt.GATT_SUCCESS)
                abortCurrent("control point write failed: ${attError(status)}")
            else if (!currentQuiet()) log("CP write acked - awaiting 0x80 response")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                INDOOR_BIKE_DATA -> parseIndoorBikeData(value)
                CP_MEASUREMENT -> parseCyclingPower(value)
                TRAINING_STATUS -> log("<- training status: ${parseTrainingStatus(value)}")
                FTMS_STATUS -> {
                    // The sim-grade loop makes the trainer repeat "simulation parameters
                    // changed" every second - only log changes.
                    val msg = parseMachineStatus(value)
                    if (msg != lastMachineStatus) { lastMachineStatus = msg; log("<- machine status: $msg") }
                }
                FTMS_CONTROL_POINT -> {
                    // 0x80 | requestOp | result (0x01 = success)
                    if (value.size < 3 || value[0] != OP_RESPONSE) {
                        log("<- malformed CP indication ${value.hex()} - ignored"); return
                    }
                    val opcode = value[1]
                    val result = value[2]
                    if (!currentQuiet() || result != RESULT_SUCCESS)
                        log("<- CP response op=0x%02x result=0x%02x".format(opcode, result))
                    // Only advance the chain if this answers the procedure we are awaiting.
                    if (!opDone(FTMS_CONTROL_POINT, opcode, null)) return
                    onCpResponse(opcode, result)
                }
            }
        }
    }

    /** FTMS Indoor Bike Data (0x2AD2): flags uint16, then optional fields in order. */
    private fun parseIndoorBikeData(v: ByteArray) {
        var i = 0
        fun u8()  = (v[i].toInt() and 0xFF).also { i += 1 }
        fun u16() = ((v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)).also { i += 2 }
        fun s16() = u16().toShort().toInt()
        fun u24() = u16() or (u8() shl 16)

        var speed = -1.0; var cadence = -1.0; var power = Int.MIN_VALUE
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
            if (flags and 0x0200 != 0) fields += "HR ${u8()} bpm"
            if (flags and 0x0400 != 0) fields += "MET %.1f".format(u8() / 10.0)
            if (flags and 0x0800 != 0) fields += "elapsed ${u16()} s"
            if (flags and 0x1000 != 0) fields += "remaining ${u16()} s"
        } catch (e: IndexOutOfBoundsException) {
            log("Short Indoor Bike Data packet (${v.size} B) - ignored")
            return
        }

        if (power != Int.MIN_VALUE) { lastPower = power; lastPowerAt = System.currentTimeMillis() }

        // Several packets per second would drown everything else in the log;
        // a periodic full dump - with the actual arrival rate - is enough.
        bikeDataCount++
        val now = System.currentTimeMillis()
        if (now - lastBikeDataLog >= BIKE_DATA_LOG_MS) {
            val hz = bikeDataCount * 1000.0 / (now - lastBikeDataLog).coerceAtMost(60_000)
            if (lastBikeDataLog != 0L) fields += "(%.1f Hz)".format(hz)
            lastBikeDataLog = now
            bikeDataCount = 0
            log("<- bike data: ${fields.joinToString(", ")}")
        }

        runOnUiThread {
            powerView.text = buildString {
                append(if (power != Int.MIN_VALUE) "$power W" else "-- W")
                append("   ")
                append(if (cadence >= 0) "${cadence.toInt()} rpm" else "-- rpm")
            }
        }
    }

    /** Cycling Power Measurement (0x2A63): flags uint16, instantaneous power sint16, then
     *  optional fields we don't need. Feeds the ERG loop; rate logged so we can see how
     *  fast this trainer actually reports. */
    private fun parseCyclingPower(v: ByteArray) {
        if (v.size < 4) { log("Short Cycling Power packet (${v.size} B) - ignored"); return }
        val p = v.s16(2)
        val now = System.currentTimeMillis()
        lastPower = p; lastPowerAt = now
        cpmCount++
        if (cpmWindowStart == 0L) { cpmWindowStart = now; return }
        if (now - cpmWindowStart >= BIKE_DATA_LOG_MS) {
            log("<- cycling power: $p W (%.1f Hz)".format(cpmCount * 1000.0 / (now - cpmWindowStart)))
            cpmCount = 0; cpmWindowStart = now
        }
    }

    // ---- FTMS informational characteristics --------------------------------

    /** Fitness Machine Feature (0x2ACC) bit names, LSB first, per the FTMS spec. */
    private val MACHINE_FEATURE_NAMES = arrayOf(
        "avg speed", "cadence", "total distance", "inclination", "elevation gain", "pace",
        "step count", "resistance level", "stride count", "expended energy", "heart rate",
        "metabolic equivalent", "elapsed time", "remaining time", "power measurement",
        "force on belt", "user data retention")
    private val TARGET_FEATURE_NAMES = arrayOf(
        "speed", "inclination", "resistance", "power", "heart rate", "expended energy",
        "step number", "stride number", "distance", "training time", "time in 2 HR zones",
        "time in 3 HR zones", "time in 5 HR zones", "bike simulation", "wheel circumference",
        "spin down", "cadence")

    private fun bitNames(bits: Int, names: Array<String>) = names
        .filterIndexed { i, _ -> bits and (1 shl i) != 0 }
        .joinToString(", ").ifEmpty { "(none)" }

    /** Pretty-prints the one-shot reads; unknown characteristics fall back to hex. */
    private fun logReadValue(uuid: UUID, v: ByteArray) {
        when (uuid) {
            MACHINE_FEATURE ->
                if (v.size < 8) log("<- machine features: short packet ${v.hex()}")
                else {
                    // Two uint32 bit fields: machine features, then target setting features.
                    val targets = v.u16(4) or (v.u16(6) shl 16)
                    targetFeatures = targets
                    log("<- machine features: ${bitNames(v.u16(0) or (v.u16(2) shl 16), MACHINE_FEATURE_NAMES)}")
                    log("<- supported targets: ${bitNames(targets, TARGET_FEATURE_NAMES)}")
                    if (targets and (1 shl 3) == 0)
                        log(if (targets and (1 shl 13) != 0)
                                "Power target unsupported - ERG will be emulated via bike-simulation grade"
                            else "Power target unsupported and no simulation either - ERG is not possible")
                }
            SUPPORTED_POWER_RANGE ->
                if (v.size < 6) log("<- power range: short packet ${v.hex()}")
                else {
                    minPower = v.s16(0); maxPower = v.s16(2); powerStep = v.u16(4).coerceAtLeast(1)
                    log("<- power range: $minPower-$maxPower W in $powerStep W steps - ERG target now clamps to this")
                    targetWatts = clampTarget(targetWatts)
                    updateTargetLabel()
                }
            SUPPORTED_RESISTANCE_RANGE ->
                if (v.size < 6) log("<- resistance range: short packet ${v.hex()}")
                else {
                    resMin = v.s16(0); resMax = v.s16(2); resStep = v.u16(4).coerceAtLeast(1)
                    log("<- resistance range: %.1f to %.1f in %.1f steps - resistance mode clamps to this"
                        .format(resMin / 10.0, resMax / 10.0, resStep / 10.0))
                    targetResRaw = clampRes(targetResRaw)
                    updateTargetLabel()
                }
            SUPPORTED_INCLINE_RANGE ->
                if (v.size < 6) log("<- incline range: short packet ${v.hex()}")
                else {
                    // Characteristic is in 0.1 % units, the sim-grade loop works in 0.01 %.
                    gradeMin = v.s16(0) * 10; gradeMax = v.s16(2) * 10
                    gradeStep = (v.u16(4) * 10).coerceAtLeast(1)
                    log("<- incline range: %.1f%% to %.1f%% in %.1f%% steps - sim grade now clamps to this"
                        .format(gradeMin / 100.0, gradeMax / 100.0, gradeStep / 100.0))
                    simGrade = simGrade.coerceIn(gradeMin, gradeMax)
                }
            TRAINING_STATUS -> log("<- training status: ${parseTrainingStatus(v)}")
            else -> log("<- read ${short(uuid)}: ${v.hex()}")
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

    // ---- HR GATT callback ---------------------------------------------------
    // No queue: the H10 issues exactly one GATT op for its whole lifetime, and the
    // one-op-at-a-time rule is per connection.
    private val hrCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("HR sensor connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log(if (status == BluetoothGatt.GATT_SUCCESS) "HR disconnected"
                    else "HR connection lost (status=$status)")
                hrGatt = null
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("HR service discovery failed (status=$status)"); return }
            val ch = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                ?: run { log("HR sensor has no Heart Rate Measurement characteristic"); return }
            log("HR measurement characteristic found, enabling notifications")
            val cccd = ch.getDescriptor(CCCD) ?: run { log("HR characteristic has no CCCD"); return }
            if (!gatt.setCharacteristicNotification(ch, true)) { log("HR setCharacteristicNotification failed"); return }
            val rc = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (rc != BluetoothStatusCodes.SUCCESS) log("HR CCCD write did not start (rc=$rc)")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("HR CCCD write failed (status=$status)")
            else log("HR notifications enabled")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != HR_MEASUREMENT) return
            // flags uint8, then uint8 or uint16 bpm depending on bit 0
            val wide = value.isNotEmpty() && value[0].toInt() and 0x01 != 0
            val need = if (wide) 3 else 2
            if (value.size < need) { log("Short HR packet (${value.size} B) - ignored"); return }
            val hr = if (wide) (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                     else value[1].toInt() and 0xFF
            runOnUiThread { hrView.text = "$hr bpm" }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan("activity destroyed")
        handler.removeCallbacksAndMessages(null)
        ops.clear(); current = null
        trainerGatt?.close(); trainerGatt = null
        hrGatt?.close(); hrGatt = null
    }
}

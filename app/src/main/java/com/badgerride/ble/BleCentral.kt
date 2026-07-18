package com.badgerride.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import com.badgerride.Mode

/**
 * Owns scanning and the two device links. One trainer + one heart-rate strap,
 * discovered by service UUID, with a bounded reconnect on unexpected drops
 * (the one thing the PoC deliberately left out).
 */
@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
    internal val handler: Handler,
    internal val targets: Targets,
    private val events: Events,
) {
    /** Mode and targets live outside the connection (they survive reconnects). */
    interface Targets {
        val mode: Mode
        val targetWatts: Int
        val targetResRaw: Int
    }

    /** All callbacks may arrive on binder threads. */
    interface Events {
        fun onChanged()
        fun onRangesChanged()
        fun onErgStatus(text: String)
    }

    enum class LinkState { IDLE, SCANNING, CONNECTING, CONNECTED }

    companion object {
        private const val TAG = "BadgerRide"
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val RECONNECT_ATTEMPTS = 5
    }

    @Volatile var trainerState = LinkState.IDLE; private set
    @Volatile var hrState = LinkState.IDLE; private set
    @Volatile internal var trainer: TrainerLink? = null
    @Volatile internal var hr: HrLink? = null
    @Volatile var scanning = false; private set

    val trainerName: String? get() = trainer?.device?.name
    val hrName: String? get() = hr?.device?.name

    // ---- Live measurements (written from GATT callbacks, read from the UI) ----
    @Volatile internal var lastPower = Int.MIN_VALUE
    @Volatile internal var lastPowerAt = 0L
    @Volatile internal var lastCadence = -1.0
    @Volatile internal var lastCadenceAt = 0L
    @Volatile internal var lastSpeedKmh = -1.0
    @Volatile internal var lastSpeedAt = 0L
    @Volatile internal var lastHr = -1
    @Volatile internal var lastHrAt = 0L

    fun powerNow(): Int? =
        if (lastPower != Int.MIN_VALUE && System.currentTimeMillis() - lastPowerAt < 3_000) lastPower else null
    fun cadenceNow(): Double? =
        if (lastCadence >= 0 && System.currentTimeMillis() - lastCadenceAt < 3_000) lastCadence else null
    fun speedNow(): Double? =
        if (lastSpeedKmh >= 0 && System.currentTimeMillis() - lastSpeedAt < 3_000) lastSpeedKmh else null
    fun hrNow(): Int? =
        if (lastHr > 0 && System.currentTimeMillis() - lastHrAt < 5_000) lastHr else null

    // ---- Trainer-reported ranges (defaults until the range reads arrive) ------
    @Volatile internal var minPower = 25
    @Volatile internal var maxPower = 500
    @Volatile internal var powerStep = 1
    @Volatile internal var resMin = 0
    @Volatile internal var resMax = 1_000
    @Volatile internal var resStep = 10

    private var trainerRetries = 0
    private var hrRetries = 0

    internal fun log(msg: String) = Log.d(TAG, msg)
    internal fun ergStatus(text: String) { log(text); events.onErgStatus(text) }
    internal fun onLiveChanged() = events.onChanged()
    internal fun onRangesChanged() = events.onRangesChanged()

    /** Applies the current mode's target immediately (± buttons, mode toggle). */
    fun pushTargets() { trainer?.pushTargets() }
    fun onModeChanged() { trainer?.onModeChanged() }

    /** Ride finished - clear the trainer's own session counters. No-op when disconnected. */
    fun resetTrainerSession() { trainer?.resetSession() }

    // ---- Scanning -----------------------------------------------------------
    // Advertisers repeat several times a second; log each device once per scan.
    private val seenDevices = HashSet<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            val dev = result.device
            if (seenDevices.add(dev.address))
                log("Found ${dev.name ?: "(unnamed)"} [${dev.address}] rssi=${result.rssi} dBm " +
                    "services=${uuids.joinToString(",") { shortUuid(it.uuid) }}")
            if (uuids.contains(ParcelUuid(Ftms.FTMS_SERVICE)) && trainer == null) {
                trainerState = LinkState.CONNECTING
                trainerRetries = 0
                trainer = TrainerLink(context, dev, handler, this@BleCentral).also { it.connect() }
                events.onChanged()
            } else if (uuids.contains(ParcelUuid(Ftms.HR_SERVICE)) && hr == null &&
                       dev.address != trainer?.device?.address) {
                // else-if + address check: some trainers relay HR, and connecting to the
                // same device twice would burn a second GATT client and strand the strap.
                hrState = LinkState.CONNECTING
                hrRetries = 0
                hr = HrLink(context, dev, this@BleCentral).also { it.connect() }
                events.onChanged()
            }
            if (trainer != null && hr != null) stopScan("both devices found")
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            if (trainerState == LinkState.SCANNING) trainerState = LinkState.IDLE
            if (hrState == LinkState.SCANNING) hrState = LinkState.IDLE
            log("Scan failed errorCode=$errorCode")
            events.onChanged()
        }
    }

    private val scanTimeoutRunnable = Runnable {
        stopScan("timed out")
        val missing = listOfNotNull(
            if (trainer == null) "trainer" else null,
            if (hr == null) "HR strap" else null
        )
        if (missing.isNotEmpty()) log("Scan timed out - no ${missing.joinToString(" or ")} found")
    }

    /** Null when there is no adapter or Bluetooth is turned off. */
    private fun scanner(): BluetoothLeScanner? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    /** Returns false when Bluetooth is off. Callers must hold the BLE permissions. */
    fun startScan(): Boolean {
        if (scanning) return true
        val scanner = scanner() ?: run {
            log("No BLE scanner available - turn Bluetooth on and try again.")
            return false
        }
        scanning = true
        seenDevices.clear()
        if (trainer == null) trainerState = LinkState.SCANNING
        if (hr == null) hrState = LinkState.SCANNING
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Ftms.FTMS_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Ftms.HR_SERVICE)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)
        log("Scan started - filtering for FTMS ${shortUuid(Ftms.FTMS_SERVICE)} and HR ${shortUuid(Ftms.HR_SERVICE)}, " +
            "${SCAN_TIMEOUT_MS / 1000}s timeout")
        // Android silently returns no results after 5 scan starts in 30s, so an
        // unstoppable scan is not harmless.
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        events.onChanged()
        return true
    }

    private fun stopScan(why: String) {
        handler.removeCallbacks(scanTimeoutRunnable)
        if (!scanning) return
        scanning = false
        scanner()?.stopScan(scanCallback)
        if (trainerState == LinkState.SCANNING) trainerState = LinkState.IDLE
        if (hrState == LinkState.SCANNING) hrState = LinkState.IDLE
        log("Scan stopped ($why)")
        events.onChanged()
    }

    // ---- Link lifecycle -----------------------------------------------------

    internal fun onTrainerUp(link: TrainerLink) {
        if (link !== trainer) return
        trainerRetries = 0
        trainerState = LinkState.CONNECTED
        events.onChanged()
    }

    internal fun onTrainerDown(link: TrainerLink) {
        if (link !== trainer) return
        if (trainerRetries++ < RECONNECT_ATTEMPTS) {
            trainerState = LinkState.CONNECTING
            log("Reconnecting to trainer in ${RECONNECT_DELAY_MS / 1000} s (attempt $trainerRetries/$RECONNECT_ATTEMPTS)…")
            handler.postDelayed({
                if (link === trainer && trainerState == LinkState.CONNECTING) link.connect()
            }, RECONNECT_DELAY_MS)
        } else {
            trainer = null
            trainerState = LinkState.IDLE
            log("Giving up on the trainer - scan again to reconnect")
        }
        events.onChanged()
    }

    internal fun onHrUp(link: HrLink) {
        if (link !== hr) return
        hrRetries = 0
        hrState = LinkState.CONNECTED
        events.onChanged()
    }

    internal fun onHrDown(link: HrLink) {
        if (link !== hr) return
        if (hrRetries++ < RECONNECT_ATTEMPTS) {
            hrState = LinkState.CONNECTING
            log("Reconnecting to HR strap in ${RECONNECT_DELAY_MS / 1000} s (attempt $hrRetries/$RECONNECT_ATTEMPTS)…")
            handler.postDelayed({
                if (link === hr && hrState == LinkState.CONNECTING) link.connect()
            }, RECONNECT_DELAY_MS)
        } else {
            hr = null
            hrState = LinkState.IDLE
            log("Giving up on the HR strap - scan again to reconnect")
        }
        events.onChanged()
    }
}

package com.badgerride

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.badgerride.ble.BleCentral
import com.badgerride.health.FinishedRide
import com.badgerride.health.HealthSync
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One per-second sample of the ride; index in [RideEngine.samples] is the moving second,
 * [at] the wall-clock time (Health Connect series need real timestamps across pauses).
 * Cadence -1 / speed -1 mean "not reported that second".
 */
class Sample(val at: Long, val power: Int, val hr: Int, val cadence: Double, val speedKmh: Double)

/**
 * Application-scoped ride state: owns the BLE hub, the mode/target values, and the
 * session accounting (moving time, distance, calories, the power/HR series). Living
 * outside any activity, the connection and the ride survive screen changes.
 */
class RideEngine(app: Context) : BleCentral.Targets, BleCentral.Events {

    companion object {
        /** No pedaling for this long ends the ride on its own. */
        private const val AUTO_FINISH_IDLE_MS = 5 * 60_000L
        /** Rides shorter than this are reset but not worth a Health Connect workout. */
        private const val MIN_EXPORT_SEC = 60
    }

    private val appContext = app.applicationContext
    val prefs = Prefs(app)
    private val handler = Handler(Looper.getMainLooper())
    val central = BleCentral(app, handler, this, this)
    val health = HealthSync(app)

    @Volatile override var mode: Mode = prefs.mode; private set
    @Volatile override var targetWatts: Int = prefs.targetWatts; private set
    @Volatile override var targetResRaw: Int = prefs.targetResRaw; private set

    /** Last detailed ERG status line ("ERG active - 150 W", "ERG unavailable: …"). */
    @Volatile var ergStatus = ""; private set
    val ergTrouble: Boolean get() = ergStatus.startsWith("ERG unavailable")

    // ---- Session ------------------------------------------------------------
    // All session state is touched only on the main thread (the 1 Hz ticker).
    val samples = ArrayList<Sample>()
    var movingSec = 0; private set
    var distanceM = 0.0; private set
    var kj = 0.0; private set
    var kcalFromHr = 0.0; private set

    /** Wall-clock time of the first / most recent moving second; 0 = no ride in progress. */
    private var rideStartMs = 0L
    private var lastMovingMs = 0L
    val rideActive: Boolean get() = rideStartMs != 0L

    /** The foreground service that keeps the process unfrozen while a ride runs. */
    private var serviceRunning = false

    val calories: Int
        get() = if (prefs.calSource == "power") kj.roundToInt() else kcalFromHr.roundToInt()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var notifyPending = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Coalesces bursts of BLE callbacks into at most one UI pass per main-loop turn. */
    private fun notifyUi() {
        if (notifyPending) return
        notifyPending = true
        handler.post {
            notifyPending = false
            for (l in listeners) l()
        }
    }

    // ---- Targets ------------------------------------------------------------

    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        prefs.mode = m
        central.onModeChanged()
        notifyUi()
    }

    fun adjustErg(deltaW: Int) {
        targetWatts = clampWatts(targetWatts + deltaW)
        prefs.targetWatts = targetWatts
        central.pushTargets()
        notifyUi()
    }

    fun adjustRes(deltaRaw: Int) {
        targetResRaw = clampRes(targetResRaw + deltaRaw)
        prefs.targetResRaw = targetResRaw
        central.pushTargets()
        notifyUi()
    }

    /** Clamps to the trainer's reported power range, rounded down to its increment. */
    private fun clampWatts(w: Int): Int {
        val min = central.minPower; val step = central.powerStep
        val stepped = if (w > min) min + (w - min) / step * step else w
        return stepped.coerceIn(min, central.maxPower)
    }

    /** Clamps to the trainer's reported resistance range, rounded down to its increment. */
    private fun clampRes(raw: Int): Int {
        val min = central.resMin; val step = central.resStep
        val c = raw.coerceIn(min, central.resMax)
        return min + (c - min) / step * step
    }

    // ---- Scan bootstrap -----------------------------------------------------

    /** Idempotent: scans only when something is still missing. Needs BLE permissions. */
    fun ensureScanning() {
        if (!central.scanning && (central.trainer == null || central.hr == null)) central.startScan()
    }

    // ---- BleCentral.Events --------------------------------------------------

    override fun onChanged() = notifyUi()

    override fun onRangesChanged() {
        targetWatts = clampWatts(targetWatts)
        targetResRaw = clampRes(targetResRaw)
        prefs.targetWatts = targetWatts
        prefs.targetResRaw = targetResRaw
        notifyUi()
    }

    override fun onErgStatus(text: String) {
        ergStatus = text
        notifyUi()
    }

    // ---- 1 Hz session ticker ------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun tick() {
        val power = central.powerNow()?.coerceAtLeast(0) ?: 0
        val hr = central.hrNow() ?: 0
        // Reported speed when the trainer sends one; otherwise the design's estimate
        // from power (v = cbrt(P / 0.24) m/s), so distance still accumulates.
        val speedKmh = central.speedNow()
            ?: if (power > 0) 3.6 * cbrt(power / 0.24) else 0.0

        val moving = power > 0 || speedKmh > 0.5
        if (moving) {
            val now = System.currentTimeMillis()
            if (rideStartMs == 0L) rideStartMs = now
            lastMovingMs = now
            samples.add(Sample(now, power, hr, central.cadenceNow() ?: -1.0, speedKmh))
            movingSec++
            distanceM += speedKmh / 3.6
            kj += power / 1000.0
            if (hr > 0) kcalFromHr += max(0.0, keytelKcalPerMin(hr)) / 60.0
        } else if (rideActive && System.currentTimeMillis() - lastMovingMs >= AUTO_FINISH_IDLE_MS) {
            Log.d("BadgerRide", "No trainer activity for ${AUTO_FINISH_IDLE_MS / 60_000} min - finishing the ride")
            finishRide()
        }
        // Normally started on the first moving sample (the Ride screen is in the
        // foreground, so it succeeds); kept as a retry in case the OS refused a
        // background start - it goes through once the app is foregrounded again.
        if (rideActive && !serviceRunning) serviceRunning = RideService.start(appContext)
        notifyUi()
    }

    /**
     * Ends the ride: snapshots it for Health Connect, resets the session accounting and
     * tells the trainer to reset its own statistics. Idempotent when nothing is running.
     * Called from the Finish Ride button and the idle watchdog (main thread only).
     */
    fun finishRide() {
        if (!rideActive) return
        // An auto-finish fires 5 min after the last pedal stroke - the ride ended back
        // then, not now. +1 s keeps the end after the last sample.
        val endMs = (lastMovingMs + 1_000).coerceAtLeast(rideStartMs + 1_000)
        val ride = FinishedRide(rideStartMs, endMs, movingSec, distanceM, calories, ArrayList(samples))

        samples.clear()
        movingSec = 0
        distanceM = 0.0
        kj = 0.0
        kcalFromHr = 0.0
        rideStartMs = 0L
        lastMovingMs = 0L
        serviceRunning = false
        RideService.stop(appContext)
        central.resetTrainerSession()

        if (ride.movingSec >= MIN_EXPORT_SEC) health.submit(ride)
        else Log.d("BadgerRide", "Ride under $MIN_EXPORT_SEC s moving - reset without exporting")
        notifyUi()
    }

    /** Keytel et al. 2005 - kcal/min from heart rate, weight, age and sex. */
    fun keytelKcalPerMin(hr: Int): Double =
        if (prefs.sex == "m")
            (-55.0969 + 0.6309 * hr + 0.1988 * prefs.weightKg + 0.2017 * prefs.age) / 4.184
        else
            (-20.4022 + 0.4472 * hr - 0.1263 * prefs.weightKg + 0.074 * prefs.age) / 4.184

    init {
        Log.d("BadgerRide", "Engine started")
        handler.postDelayed(ticker, 1_000L)
    }
}

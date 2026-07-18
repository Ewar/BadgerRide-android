package com.badgerride.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.health.connect.client.PermissionController
import com.badgerride.Mode
import com.badgerride.R
import com.badgerride.RideEngine
import com.badgerride.Zones
import com.badgerride.engine

class RideActivity : ComponentActivity() {

    private lateinit var eng: RideEngine

    private lateinit var powerValue: TextView
    private lateinit var cadenceValue: TextView
    private lateinit var hrValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var speedUnit: TextView
    private lateinit var distValue: TextView
    private lateinit var distUnit: TextView
    private lateinit var timeValue: TextView
    private lateinit var calValue: TextView
    private lateinit var chart: PowerHrChart
    private lateinit var histogram: ZoneHistogram
    private lateinit var segErg: TextView
    private lateinit var segRes: TextView
    private lateinit var btnMinusBig: TextView
    private lateinit var btnMinusSml: TextView
    private lateinit var btnPlusSml: TextView
    private lateinit var btnPlusBig: TextView
    private lateinit var targetValue: TextView
    private lateinit var targetSub: TextView
    private lateinit var statusTag: TextView

    private val renderListener: () -> Unit = { render() }

    private val requiredPerms =
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    /** Requested but not required: a denied notification permission only hides the
     *  ride-service notification, it must never block scanning. */
    private val wantedPerms = requiredPerms + Manifest.permission.POST_NOTIFICATIONS

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermissions()) eng.ensureScanning()
            // Chained after the BLE dialog rather than launched alongside it - two
            // system dialogs stacked at first start is a mess.
            eng.health.ensurePermissions { missing -> healthPermLauncher.launch(missing) }
            render()
        }

    private val healthPermLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            eng.health.onPermissionsResult(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eng = engine
        setContentView(R.layout.activity_ride)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.lightSystemBars()

        val d = resources.displayMetrics.density
        findViewById<LinearLayout>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding((14 * d).toInt() + bars.left, (8 * d).toInt() + bars.top,
                         (14 * d).toInt() + bars.right, (6 * d).toInt() + bars.bottom)
            insets
        }

        powerValue = findViewById(R.id.powerValue)
        cadenceValue = findViewById(R.id.cadenceValue)
        hrValue = findViewById(R.id.hrValue)
        speedValue = findViewById(R.id.speedValue)
        speedUnit = findViewById(R.id.speedUnit)
        distValue = findViewById(R.id.distValue)
        distUnit = findViewById(R.id.distUnit)
        timeValue = findViewById(R.id.timeValue)
        calValue = findViewById(R.id.calValue)
        chart = findViewById(R.id.chart)
        histogram = findViewById(R.id.histogram)
        segErg = findViewById(R.id.segErg)
        segRes = findViewById(R.id.segRes)
        btnMinusBig = findViewById(R.id.btnMinusBig)
        btnMinusSml = findViewById(R.id.btnMinusSml)
        btnPlusSml = findViewById(R.id.btnPlusSml)
        btnPlusBig = findViewById(R.id.btnPlusBig)
        targetValue = findViewById(R.id.targetValue)
        targetSub = findViewById(R.id.targetSub)
        statusTag = findViewById(R.id.statusTag)

        buildZoneLegend()

        segErg.setOnClickListener { eng.setMode(Mode.ERG) }
        segRes.setOnClickListener { eng.setMode(Mode.RESISTANCE) }
        btnMinusBig.setOnClickListener { adjust(-25, -50) }
        btnMinusSml.setOnClickListener { adjust(-10, -10) }
        btnPlusSml.setOnClickListener { adjust(+10, +10) }
        btnPlusBig.setOnClickListener { adjust(+25, +50) }
        // While a ride is running the tag is the Finish Ride button; otherwise it
        // reports connection state and opens Settings (still reachable via the brand).
        statusTag.setOnClickListener {
            if (eng.rideActive) confirmFinish()
            else startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.brand).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val missing = wantedPerms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            eng.ensureScanning()
            eng.health.ensurePermissions { m -> healthPermLauncher.launch(m) }
        } else {
            if (hasPermissions()) eng.ensureScanning()   // only notifications are missing
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun confirmFinish() {
        AlertDialog.Builder(this)
            .setTitle("Finish ride?")
            .setMessage("The ride will be saved to Health Connect and the statistics reset.")
            .setPositiveButton("Finish") { _, _ -> eng.finishRide() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun adjust(ergDeltaW: Int, resDeltaRaw: Int) {
        if (eng.mode == Mode.ERG) eng.adjustErg(ergDeltaW) else eng.adjustRes(resDeltaRaw)
    }

    private fun hasPermissions() = requiredPerms.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildZoneLegend() {
        val legend = findViewById<LinearLayout>(R.id.zoneLegend)
        val d = resources.displayMetrics.density
        for (i in Zones.names.indices) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (i > 0) layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (9 * d).toInt() }
            }
            chip.addView(android.view.View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 2 * d
                    setColor(Zones.tint(Zones.colors[i]))
                    setStroke((1 * d).toInt(), Zones.colors[i])
                }
                layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt())
            })
            chip.addView(TextView(this).apply {
                text = "Z${i + 1} ${Zones.names[i]}"
                textSize = 8.5f
                setTextColor(getColor(R.color.text_muted))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (3 * d).toInt() }
            })
            legend.addView(chip)
        }
    }

    override fun onStart() {
        super.onStart()
        eng.addListener(renderListener)
        render()
    }

    override fun onStop() {
        super.onStop()
        eng.removeListener(renderListener)
    }

    private fun render() {
        val c = eng.central
        powerValue.setTextIfChanged(c.powerNow()?.toString() ?: "––")
        cadenceValue.setTextIfChanged(c.cadenceNow()?.let { "%.0f".format(it) } ?: "––")
        hrValue.setTextIfChanged(c.hrNow()?.toString() ?: "––")

        val imp = eng.prefs.imperial
        val speed = c.speedNow() ?: 0.0
        speedValue.setTextIfChanged("%.1f".format(if (imp) speed * 0.6214 else speed))
        speedUnit.setTextIfChanged(if (imp) "mph" else "km/h")
        val km = eng.distanceM / 1000.0
        distValue.setTextIfChanged("%.1f".format(if (imp) km * 0.6214 else km))
        distUnit.setTextIfChanged(if (imp) "mi" else "km")
        timeValue.setTextIfChanged(fmtTime(eng.movingSec))
        calValue.setTextIfChanged(eng.calories.toString())

        val erg = eng.mode == Mode.ERG
        chart.update(eng.samples, eng.targetWatts,
            erg && c.trainerState == com.badgerride.ble.BleCentral.LinkState.CONNECTED)
        histogram.update(eng.samples, eng.prefs.maxHr)

        segErg.isSelected = erg
        segRes.isSelected = !erg
        btnMinusBig.setTextIfChanged(if (erg) "−25" else "−5")
        btnMinusSml.setTextIfChanged(if (erg) "−10" else "−1")
        btnPlusSml.setTextIfChanged(if (erg) "+10" else "+1")
        btnPlusBig.setTextIfChanged(if (erg) "+25" else "+5")
        targetValue.setTextIfChanged(
            if (erg) "${eng.targetWatts} W"
            else "Level ${fmtResLevel(eng.targetResRaw)}")
        targetSub.setTextIfChanged(if (erg) "ERG target" else "Resistance")

        val connected = c.trainerState == com.badgerride.ble.BleCentral.LinkState.CONNECTED
        statusTag.setTextIfChanged(when {
            eng.rideActive -> "Finish Ride"
            connected && eng.ergTrouble -> "ERG unavailable"
            connected -> "FTMS · Connected"
            c.trainerState == com.badgerride.ble.BleCentral.LinkState.CONNECTING -> "FTMS · Connecting…"
            c.scanning -> "Scanning…"
            !hasPermissions() -> "Bluetooth permission needed"
            else -> "Not connected"
        })
        val accent = connected || eng.rideActive
        statusTag.setBackgroundResource(if (accent) R.drawable.tag_outline else R.drawable.tag_outline_muted)
        statusTag.setTextColor(getColor(if (accent) R.color.accent else R.color.text_muted))
    }

    private fun fmtResLevel(raw: Int): String =
        if (raw % 10 == 0) (raw / 10).toString() else "%.1f".format(raw / 10.0)
}

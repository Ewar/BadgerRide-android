package com.badgerride.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.widget.doAfterTextChanged
import com.badgerride.R
import com.badgerride.RideEngine
import com.badgerride.Zones
import com.badgerride.ble.BleCentral
import com.badgerride.engine
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {

    private lateinit var eng: RideEngine

    private lateinit var trainerSub: TextView
    private lateinit var trainerTag: TextView
    private lateinit var hrSub: TextView
    private lateinit var hrTag: TextView
    private lateinit var scanBtn: TextView
    private lateinit var maxHrInput: EditText
    private lateinit var zonesList: LinearLayout
    private lateinit var calPower: TextView
    private lateinit var calHr: TextView
    private lateinit var calNote: TextView
    private lateinit var weightInput: EditText
    private lateinit var ageInput: EditText
    private lateinit var segFemale: TextView
    private lateinit var segMale: TextView
    private lateinit var segMetric: TextView
    private lateinit var segImperial: TextView

    private var zonesBuiltFor = -1

    private val renderListener: () -> Unit = { render() }

    private val requiredPerms =
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) eng.central.startScan()
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eng = engine
        setContentView(R.layout.activity_settings)
        window.lightSystemBars()

        val d = resources.displayMetrics.density
        findViewById<LinearLayout>(R.id.settingsRoot).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding((18 * d).toInt() + bars.left, (14 * d).toInt() + bars.top,
                         (18 * d).toInt() + bars.right, (20 * d).toInt() + bars.bottom)
            insets
        }

        trainerSub = findViewById(R.id.trainerSub)
        trainerTag = findViewById(R.id.trainerTag)
        hrSub = findViewById(R.id.hrSub)
        hrTag = findViewById(R.id.hrTag)
        scanBtn = findViewById(R.id.scanBtn)
        maxHrInput = findViewById(R.id.maxHrInput)
        zonesList = findViewById(R.id.zonesList)
        calPower = findViewById(R.id.calPower)
        calHr = findViewById(R.id.calHr)
        calNote = findViewById(R.id.calNote)
        weightInput = findViewById(R.id.weightInput)
        ageInput = findViewById(R.id.ageInput)
        segFemale = findViewById(R.id.segFemale)
        segMale = findViewById(R.id.segMale)
        segMetric = findViewById(R.id.segMetric)
        segImperial = findViewById(R.id.segImperial)

        maxHrInput.setText(eng.prefs.maxHr.toString())
        weightInput.setText(eng.prefs.weightKg.toString())
        ageInput.setText(eng.prefs.age.toString())

        maxHrInput.doAfterTextChanged {
            it.toString().toIntOrNull()?.takeIf { v -> v in 120..230 }?.let { v ->
                eng.prefs.maxHr = v; render()
            }
        }
        weightInput.doAfterTextChanged {
            it.toString().toIntOrNull()?.takeIf { v -> v in 30..250 }?.let { v ->
                eng.prefs.weightKg = v; render()
            }
        }
        ageInput.doAfterTextChanged {
            it.toString().toIntOrNull()?.takeIf { v -> v in 10..110 }?.let { v ->
                eng.prefs.age = v; render()
            }
        }

        scanBtn.setOnClickListener {
            if (hasPermissions()) eng.central.startScan()
            else permissionLauncher.launch(requiredPerms)
        }
        calPower.setOnClickListener { eng.prefs.calSource = "power"; render() }
        calHr.setOnClickListener { eng.prefs.calSource = "hr"; render() }
        segFemale.setOnClickListener { eng.prefs.sex = "f"; render() }
        segMale.setOnClickListener { eng.prefs.sex = "m"; render() }
        segMetric.setOnClickListener { eng.prefs.imperial = false; render() }
        segImperial.setOnClickListener { eng.prefs.imperial = true; render() }
    }

    private fun hasPermissions() = requiredPerms.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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

        trainerSub.setTextIfChanged(c.trainerName?.let { "$it · FTMS over Bluetooth" }
            ?: "FTMS over Bluetooth")
        applyTag(trainerTag, c.trainerState)
        hrSub.setTextIfChanged(c.hrName?.let { "$it · Bluetooth" } ?: "Bluetooth heart-rate strap")
        applyTag(hrTag, c.hrState)
        scanBtn.setTextIfChanged(if (c.scanning) "Scanning…" else getString(R.string.scan_for_devices))

        if (zonesBuiltFor != eng.prefs.maxHr) buildZones()

        val fromPower = eng.prefs.calSource == "power"
        calPower.isSelected = fromPower
        calHr.isSelected = !fromPower
        calNote.setTextIfChanged(
            if (fromPower)
                "1 kJ of work at the pedals ≈ 1 kcal burned. This ride: ${eng.kj.roundToInt()} kcal."
            else
                "Keytel estimate from heart rate, weight, age and sex. This ride: ${eng.kcalFromHr.roundToInt()} kcal.")

        segFemale.isSelected = eng.prefs.sex == "f"
        segMale.isSelected = eng.prefs.sex == "m"
        segMetric.isSelected = !eng.prefs.imperial
        segImperial.isSelected = eng.prefs.imperial
    }

    private fun applyTag(tag: TextView, state: BleCentral.LinkState) {
        val connected = state == BleCentral.LinkState.CONNECTED
        tag.setTextIfChanged(when (state) {
            BleCentral.LinkState.CONNECTED -> "Connected"
            BleCentral.LinkState.CONNECTING -> "Connecting…"
            BleCentral.LinkState.SCANNING -> "Scanning…"
            BleCentral.LinkState.IDLE -> "Not connected"
        })
        tag.setBackgroundResource(if (connected) R.drawable.tag_outline else R.drawable.tag_outline_muted)
        tag.setTextColor(getColor(if (connected) R.color.accent else R.color.text_muted))
    }

    private fun buildZones() {
        zonesBuiltFor = eng.prefs.maxHr
        zonesList.removeAllViews()
        val d = resources.displayMetrics.density
        val maxHr = eng.prefs.maxHr
        for (i in Zones.names.indices) {
            val a = (Zones.lowPct[i] / 100.0 * maxHr).roundToInt()
            val b = (Zones.highPct[i] / 100.0 * maxHr).roundToInt()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (7 * d).toInt(), 0, (7 * d).toInt())
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 2 * d
                    setColor(Zones.tint(Zones.colors[i]))
                    setStroke((1 * d).toInt(), Zones.colors[i])
                }
                layoutParams = LinearLayout.LayoutParams((12 * d).toInt(), (12 * d).toInt())
            })
            row.addView(TextView(this).apply {
                text = "Z${i + 1} · ${Zones.names[i]}"
                textSize = 13.5f
                setTextColor(getColor(R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = (10 * d).toInt() }
            })
            row.addView(TextView(this).apply {
                text = if (i == 0) "< 60%" else "${Zones.lowPct[i]}–${Zones.highPct[i]}%"
                textSize = 12f
                setTextColor(getColor(R.color.text_muted))
            })
            row.addView(TextView(this).apply {
                text = when (i) {
                    0 -> "< $b bpm"
                    4 -> "$a–$maxHr bpm"
                    else -> "$a–$b bpm"
                }
                textSize = 13f
                gravity = Gravity.END
                fontFeatureSettings = "'tnum'"
                setTextColor(getColor(R.color.text))
                layoutParams = LinearLayout.LayoutParams((100 * d).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            zonesList.addView(row)
            zonesList.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt())
            })
        }
    }
}

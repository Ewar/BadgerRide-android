package com.badgerride

import android.content.Context
import androidx.core.content.edit

enum class Mode { ERG, RESISTANCE }

/** Persisted settings; defaults match the design's initial state. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("badgerride", Context.MODE_PRIVATE)

    var maxHr: Int
        get() = sp.getInt("maxHr", 185)
        set(v) = sp.edit { putInt("maxHr", v) }

    var weightKg: Int
        get() = sp.getInt("weightKg", 76)
        set(v) = sp.edit { putInt("weightKg", v) }

    var age: Int
        get() = sp.getInt("age", 38)
        set(v) = sp.edit { putInt("age", v) }

    /** "m" or "f" - feeds the Keytel calorie estimate. */
    var sex: String
        get() = sp.getString("sex", "m") ?: "m"
        set(v) = sp.edit { putString("sex", v) }

    /** "power" (1 kJ ≈ 1 kcal) or "hr" (Keytel). */
    var calSource: String
        get() = sp.getString("calSource", "power") ?: "power"
        set(v) = sp.edit { putString("calSource", v) }

    var imperial: Boolean
        get() = sp.getBoolean("imperial", false)
        set(v) = sp.edit { putBoolean("imperial", v) }

    var mode: Mode
        get() = if (sp.getString("mode", "erg") == "res") Mode.RESISTANCE else Mode.ERG
        set(v) = sp.edit { putString("mode", if (v == Mode.RESISTANCE) "res" else "erg") }

    var targetWatts: Int
        get() = sp.getInt("targetWatts", 150)
        set(v) = sp.edit { putInt("targetWatts", v) }

    /** Resistance target in the characteristic's raw 0.1 unitless units. */
    var targetResRaw: Int
        get() = sp.getInt("targetResRaw", 100)
        set(v) = sp.edit { putInt("targetResRaw", v) }
}

package com.badgerride

/** Five heart-rate zones as a share of max HR, with the design's zone palette. */
object Zones {
    val colors = intArrayOf(
        0xFF9B9797.toInt(),   // Z1 Recovery  (neutral-500)
        0xFF6C8A65.toInt(),   // Z2 Endurance (oklch 0.60 0.065 140)
        0xFFC28D41.toInt(),   // Z3 Tempo     (accent-500)
        0xFFC16E2D.toInt(),   // Z4 Threshold (oklch 0.62 0.13 55)
        0xFFA83825.toInt(),   // Z5 Max       (oklch 0.50 0.15 32)
    )
    val names = arrayOf("Recovery", "Endurance", "Tempo", "Threshold", "Max")
    val lowPct = intArrayOf(50, 60, 70, 80, 90)
    val highPct = intArrayOf(60, 70, 80, 90, 100)

    fun index(hr: Int, maxHr: Int): Int {
        val r = hr.toDouble() / maxHr
        return when {
            r < 0.6 -> 0
            r < 0.7 -> 1
            r < 0.8 -> 2
            r < 0.9 -> 3
            else -> 4
        }
    }

    /** color-mix(zone 28%, transparent) - the bar/chip fill. */
    fun tint(color: Int) = (color and 0x00FFFFFF) or (0x47 shl 24)
}

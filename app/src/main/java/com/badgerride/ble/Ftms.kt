package com.badgerride.ble

import java.util.UUID

internal fun uuid16(v: Int): UUID =
    UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

/** "0x2ad9" - for log lines. */
internal fun shortUuid(u: UUID) =
    "0x%04x".format((u.mostSignificantBits shr 32).toInt() and 0xFFFF)

/** Short form for SIG-assigned UUIDs, full form for vendor ones. */
internal fun uuidLabel(u: UUID) =
    if (u.toString().endsWith("-0000-1000-8000-00805f9b34fb")) shortUuid(u) else u.toString()

internal fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

/** Little-endian field access for fixed-layout characteristics. */
internal fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
internal fun ByteArray.s16(i: Int) = u16(i).toShort().toInt()

/** FTMS / Cycling Power / Heart Rate UUIDs and control-point constants. */
internal object Ftms {
    val FTMS_SERVICE = uuid16(0x1826)
    val MACHINE_FEATURE = uuid16(0x2ACC)             // read: capability bits
    val INDOOR_BIKE_DATA = uuid16(0x2AD2)            // notify: speed/cadence/power
    val TRAINING_STATUS = uuid16(0x2AD3)             // read + notify: workout state
    val SUPPORTED_INCLINE_RANGE = uuid16(0x2AD5)     // read
    val SUPPORTED_RESISTANCE_RANGE = uuid16(0x2AD6)  // read
    val SUPPORTED_POWER_RANGE = uuid16(0x2AD8)       // read
    val FTMS_CONTROL_POINT = uuid16(0x2AD9)          // write + indicate: ERG commands
    val FTMS_STATUS = uuid16(0x2ADA)                 // notify (optional)

    val CPS_SERVICE = uuid16(0x1818)                 // Cycling Power - often notifies faster than FTMS
    val CP_MEASUREMENT = uuid16(0x2A63)

    val HR_SERVICE = uuid16(0x180D)
    val HR_MEASUREMENT = uuid16(0x2A37)

    val CCCD = uuid16(0x2902)

    const val OP_REQUEST_CONTROL: Byte = 0x00
    const val OP_RESET: Byte = 0x01
    const val OP_SET_RESISTANCE: Byte = 0x04
    const val OP_SET_TARGET_POWER: Byte = 0x05
    const val OP_START_RESUME: Byte = 0x07
    const val OP_SIM_PARAMS: Byte = 0x11
    const val OP_RESPONSE: Byte = -0x80              // 0x80
    const val RESULT_SUCCESS: Byte = 0x01

    /** ATT error codes the FTMS control point uses; 128 is the classic pipelining symptom. */
    fun attError(status: Int) = when (status) {
        128 -> "0x80 control point procedure already in progress"
        129 -> "0x81 CCCD improperly configured"
        else -> "status=$status"
    }

    /** Fitness Machine Feature (0x2ACC) bit names, LSB first, per the FTMS spec. */
    val MACHINE_FEATURE_NAMES = arrayOf(
        "avg speed", "cadence", "total distance", "inclination", "elevation gain", "pace",
        "step count", "resistance level", "stride count", "expended energy", "heart rate",
        "metabolic equivalent", "elapsed time", "remaining time", "power measurement",
        "force on belt", "user data retention")
    val TARGET_FEATURE_NAMES = arrayOf(
        "speed", "inclination", "resistance", "power", "heart rate", "expended energy",
        "step number", "stride number", "distance", "training time", "time in 2 HR zones",
        "time in 3 HR zones", "time in 5 HR zones", "bike simulation", "wheel circumference",
        "spin down", "cadence")

    fun bitNames(bits: Int, names: Array<String>) = names
        .filterIndexed { i, _ -> bits and (1 shl i) != 0 }
        .joinToString(", ").ifEmpty { "(none)" }
}

package com.badgerride.health

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import com.badgerride.Sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Immutable snapshot of a ride taken at finish time, after which the live state resets. */
class FinishedRide(
    val startMs: Long,
    val endMs: Long,
    val movingSec: Int,
    val distanceM: Double,
    val kcal: Int,
    val samples: List<Sample>,
)

/**
 * Writes finished rides to Health Connect as a stationary-biking exercise session with
 * distance, active calories and the per-second heart-rate / power / speed / cadence series.
 *
 * A ride that cannot be written yet (permissions not granted) is kept as [pending] and
 * retried when the permission result arrives - so an auto-finished ride from before the
 * first grant is not lost. Only the latest unfinished upload is kept.
 */
class HealthSync(private val context: Context) {

    companion object {
        private const val TAG = "BadgerRide"
        /** ~17 min of 1 Hz samples per series record - keeps binder transactions small. */
        private const val CHUNK = 1_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(PowerRecord::class),
        HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
    )

    @Volatile private var pending: FinishedRide? = null

    /**
     * Checks whether the write permissions are granted and hands the missing set to
     * [onMissing] (on the main thread) so the activity can launch the request contract.
     * When everything is already granted, flushes any ride waiting from a previous denial.
     */
    fun ensurePermissions(onMissing: (Set<String>) -> Unit) {
        if (!available) return
        scope.launch {
            try {
                val granted = HealthConnectClient.getOrCreate(context)
                    .permissionController.getGrantedPermissions()
                val missing = permissions - granted
                if (missing.isEmpty()) flush() else onMissing(missing)
            } catch (e: Exception) {
                Log.d(TAG, "Health Connect permission check failed: $e")
            }
        }
    }

    /** Result of the permission request contract - retry whatever is waiting. */
    fun onPermissionsResult(granted: Set<String>) {
        if (granted.isNotEmpty()) flush()
        else Log.d(TAG, "Health Connect permissions denied - rides will not be exported")
    }

    /** Queues the ride and tries to write it now. */
    fun submit(ride: FinishedRide) {
        if (!available) {
            Log.d(TAG, "Health Connect unavailable - ride not exported")
            return
        }
        pending = ride
        flush()
    }

    private fun flush() {
        val ride = pending ?: return
        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                if ((permissions - client.permissionController.getGrantedPermissions()).isNotEmpty()) {
                    Log.d(TAG, "Health Connect permissions missing - ride kept pending")
                    toast("Ride not saved - Health Connect permission missing")
                    return@launch
                }
                client.insertRecords(buildRecords(ride))
                pending = null
                val km = ride.distanceM / 1000.0
                Log.d(TAG, "Ride exported to Health Connect: ${ride.movingSec} s moving, " +
                    "%.1f km, ${ride.kcal} kcal, ${ride.samples.size} samples".format(km))
                toast("Ride saved to Health Connect - %d min, %.1f km, %d kcal"
                    .format(ride.movingSec / 60, km, ride.kcal))
            } catch (e: Exception) {
                // Kept pending: a later permission grant or the next finish retries it.
                Log.d(TAG, "Health Connect insert failed: $e")
                toast("Saving ride to Health Connect failed")
            }
        }
    }

    // ---- Record building ----------------------------------------------------

    private fun buildRecords(ride: FinishedRide): List<Record> {
        val start = Instant.ofEpochMilli(ride.startMs)
        val end = Instant.ofEpochMilli(ride.endMs)
        val startOff = offsetAt(start)
        val endOff = offsetAt(end)
        // The trainer and the strap are the actual sources; Health Connect only knows
        // device *types*, so the trainer's series carry TYPE_UNKNOWN.
        val phone = Metadata.autoRecorded(Device(type = Device.TYPE_PHONE))
        val strap = Metadata.autoRecorded(Device(type = Device.TYPE_CHEST_STRAP))
        val trainer = Metadata.autoRecorded(Device(type = Device.TYPE_UNKNOWN))

        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                metadata = phone,
                startTime = start, startZoneOffset = startOff,
                endTime = end, endZoneOffset = endOff,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
                title = "BadgerRide indoor ride",
            )
        )
        if (ride.distanceM > 0) records += DistanceRecord(
            metadata = trainer,
            startTime = start, startZoneOffset = startOff,
            endTime = end, endZoneOffset = endOff,
            distance = Length.meters(ride.distanceM),
        )
        if (ride.kcal > 0) records += ActiveCaloriesBurnedRecord(
            metadata = trainer,
            startTime = start, startZoneOffset = startOff,
            endTime = end, endZoneOffset = endOff,
            energy = Energy.kilocalories(ride.kcal.toDouble()),
        )

        // Series records: chunked so multi-hour rides do not blow the binder size limit.
        // Sample times sit inside [chunk start, chunk end); the +1 s covers the last one,
        // clamped so a chunk never overlaps the next (the 1 Hz ticker drifts a little).
        val chunks = ride.samples.chunked(CHUNK)
        for ((i, chunk) in chunks.withIndex()) {
            val cs = Instant.ofEpochMilli(chunk.first().at)
            val ce = Instant.ofEpochMilli(
                (chunk.last().at + 1_000).coerceAtMost(
                    chunks.getOrNull(i + 1)?.first()?.at ?: Long.MAX_VALUE))
            val cso = offsetAt(cs)
            val ceo = offsetAt(ce)

            val hr = chunk.filter { it.hr in 1..300 }.map {
                HeartRateRecord.Sample(Instant.ofEpochMilli(it.at), it.hr.toLong())
            }
            if (hr.isNotEmpty()) records += HeartRateRecord(
                metadata = strap,
                startTime = cs, startZoneOffset = cso, endTime = ce, endZoneOffset = ceo,
                samples = hr,
            )

            records += PowerRecord(
                metadata = trainer,
                startTime = cs, startZoneOffset = cso, endTime = ce, endZoneOffset = ceo,
                samples = chunk.map {
                    PowerRecord.Sample(Instant.ofEpochMilli(it.at), Power.watts(it.power.toDouble()))
                },
            )

            val speed = chunk.filter { it.speedKmh >= 0 }.map {
                SpeedRecord.Sample(Instant.ofEpochMilli(it.at), Velocity.kilometersPerHour(it.speedKmh))
            }
            if (speed.isNotEmpty()) records += SpeedRecord(
                metadata = trainer,
                startTime = cs, startZoneOffset = cso, endTime = ce, endZoneOffset = ceo,
                samples = speed,
            )

            val cadence = chunk.filter { it.cadence >= 0 }.map {
                CyclingPedalingCadenceRecord.Sample(Instant.ofEpochMilli(it.at), it.cadence)
            }
            if (cadence.isNotEmpty()) records += CyclingPedalingCadenceRecord(
                metadata = trainer,
                startTime = cs, startZoneOffset = cso, endTime = ce, endZoneOffset = ceo,
                samples = cadence,
            )
        }
        return records
    }

    private fun offsetAt(t: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(t)

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}

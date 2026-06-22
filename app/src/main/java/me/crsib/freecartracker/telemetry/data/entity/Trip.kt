package me.crsib.freecartracker.telemetry.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.data.entity.Vehicle

/**
 * One recording session (a drive). Parent of [GpsPoint], [ObdSample],
 * [ObdExtendedSample] and [DtcRecord]. Aggregate fields are null while
 * [status] is [TripStatus.RECORDING] and filled in on stop by the TripAggregator.
 */
@Entity(
    tableName = "trips",
    foreignKeys = [
        ForeignKey(
            entity = Vehicle::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("vehicleId", "startedAtMillis"),
    ],
)
@Serializable
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val startedAtMillis: Long,
    /** Null while recording is in progress. */
    val endedAtMillis: Long? = null,
    val status: TripStatus = TripStatus.RECORDING,
    // Denormalized aggregates, computed on stop so the trip list needs no rescans.
    val distanceKm: Double? = null,
    val durationSec: Long? = null,
    val avgSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    /** Seconds spent moving (speed above the idle threshold). */
    val movingSec: Long? = null,
    val fuelUsedL: Double? = null,
    val energyUsedKwh: Double? = null,
    val avgConsumptionL100: Double? = null,
    val obdSampleCount: Int = 0,
    val gpsPointCount: Int = 0,
    /** Transport used for OBD, or [ObdTransportKind.NONE] for a GPS-only trip. */
    val transport: ObdTransportKind? = null,
    /** Key of the PID profile used (e.g. "GENERIC", "TOYOTA"). */
    val profileKey: String? = null,
    val note: String? = null,
)

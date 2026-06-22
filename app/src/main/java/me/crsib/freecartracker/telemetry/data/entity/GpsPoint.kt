package me.crsib.freecartracker.telemetry.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A single GPS fix recorded during a [Trip]. Canonical units: metres, km/h, degrees. */
@Entity(
    tableName = "gps_points",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("tripId", "timestampMillis"),
    ],
)
@Serializable
data class GpsPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    val speedKmh: Double? = null,
    val bearingDeg: Float? = null,
    val accuracyM: Float? = null,
    /** Source provider: "gps", "fused" or "network". */
    val provider: String,
)

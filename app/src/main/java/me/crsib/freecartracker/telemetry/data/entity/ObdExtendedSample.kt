package me.crsib.freecartracker.telemetry.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Open-ended (tall) store for manufacturer-specific Mode 22 metrics — EV state of charge,
 * electric-motor temperature, etc. — keyed by a stable [metricKey] from the PID profile,
 * so new vehicle makes need no schema change.
 */
@Entity(
    tableName = "obd_extended_samples",
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
        Index("metricKey"),
    ],
)
@Serializable
data class ObdExtendedSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampMillis: Long,
    /** Stable metric id from the PID registry, e.g. "EV_SOC", "MOTOR_TEMP". */
    val metricKey: String,
    val value: Double,
    /** Canonical unit token, e.g. "PCT", "C", "KW". */
    val unit: String,
)

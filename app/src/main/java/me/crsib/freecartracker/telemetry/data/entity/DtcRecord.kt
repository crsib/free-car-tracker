package me.crsib.freecartracker.telemetry.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A diagnostic trouble code (e.g. "P0420") captured during a [Trip]. */
@Entity(
    tableName = "dtc_records",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("tripId"),
    ],
)
@Serializable
data class DtcRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampMillis: Long,
    val code: String,
    /** True for a pending code (Mode 07), false for a confirmed/stored code (Mode 03). */
    val pending: Boolean = false,
)

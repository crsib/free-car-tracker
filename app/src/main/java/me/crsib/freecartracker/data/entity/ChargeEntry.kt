package me.crsib.freecartracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.crsib.freecartracker.domain.model.ChargeType

@Entity(
    tableName = "charge_entries",
    foreignKeys = [
        ForeignKey(
            entity = Vehicle::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("vehicleId", "dateMillis"),
        Index("vehicleId", "odometerKm"),
    ],
)
@Serializable
data class ChargeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val dateMillis: Long,
    /** Optional odometer reading at the charge, in km. */
    val odometerKm: Double? = null,
    /** Energy added, in kWh (canonical). */
    val energyKwh: Double,
    /** Total paid, in currency minor units. Zero for free/home charging is allowed. */
    val totalCostMinor: Long,
    val currency: String,
    val chargeType: ChargeType = ChargeType.AC,
    val startPct: Int? = null,
    val endPct: Int? = null,
    val location: String? = null,
    val note: String? = null,
)

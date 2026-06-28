package me.crsib.freecartracker.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.crsib.freecartracker.domain.model.FuelType
import java.util.UUID

@Entity(
    tableName = "fuel_entries",
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
        Index("syncId", unique = true),
    ],
)
@Serializable
data class FuelEntry(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val vehicleId: Long,
    val dateMillis: Long,
    /** Odometer reading at the fill, in km (canonical). */
    val odometerKm: Double,
    /** Fuel added, in litres (canonical). */
    val volumeL: Double,
    /** Total paid, in currency minor units (e.g. cents). */
    val totalCostMinor: Long,
    val currency: String,
    /** True when the tank was filled to full — required for accurate economy. */
    val isFullTank: Boolean,
    val fuelType: FuelType? = null,
    val station: String? = null,
    val note: String? = null,
    @ColumnInfo(defaultValue = "") override val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") override val updatedAtMillis: Long = System.currentTimeMillis(),
) : LedgerRow

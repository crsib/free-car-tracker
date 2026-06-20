package me.crsib.freecartracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.crsib.freecartracker.domain.model.FuelType
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.UnitSystem

@Serializable
@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val plate: String? = null,
    val powertrain: Powertrain,
    val fuelType: FuelType? = null,
    /** Tank capacity in litres (canonical). */
    val tankCapacityL: Double? = null,
    /** Usable battery capacity in kWh (canonical). */
    val batteryCapacityKwh: Double? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** ISO 4217 currency code, e.g. "USD". */
    val currency: String = "USD",
    /** Odometer reading (km) when tracking began. */
    val initialOdometerKm: Double = 0.0,
    /** Optional display colour as packed ARGB. */
    val colorArgb: Int? = null,
    val isArchived: Boolean = false,
    val createdAt: Long,
)

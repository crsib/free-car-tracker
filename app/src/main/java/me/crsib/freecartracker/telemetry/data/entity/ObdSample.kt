package me.crsib.freecartracker.telemetry.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One poll cycle of standard OBD-II (Mode 01) metrics during a [Trip]. Every metric
 * column is nullable because adapters and ECUs support different PID subsets. Manufacturer
 * (Mode 22) metrics live separately in [ObdExtendedSample]. Source PIDs noted per field.
 */
@Entity(
    tableName = "obd_samples",
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
data class ObdSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampMillis: Long,
    // Core driving dynamics
    val speedKmh: Double? = null, // PID 0x0D
    val rpm: Double? = null, // 0x0C
    val throttlePct: Double? = null, // 0x11
    val engineLoadPct: Double? = null, // 0x04
    val runTimeSec: Int? = null, // 0x1F
    // Engine health
    val coolantTempC: Double? = null, // 0x05
    val intakeAirTempC: Double? = null, // 0x0F
    val ambientAirTempC: Double? = null, // 0x46
    val mafGramsPerSec: Double? = null, // 0x10
    val manifoldPressureKpa: Double? = null, // 0x0B
    val controlModuleVoltage: Double? = null, // 0x42 (12V system)
    // Fuel & energy
    val fuelLevelPct: Double? = null, // 0x2F
    val fuelRateLph: Double? = null, // 0x5E (litres/hour)
    /** Derived instantaneous consumption (L/100km) from fuel rate or MAF + speed. */
    val derivedL100: Double? = null,
    /** Derived CO2 (g/km) from fuel consumption. */
    val derivedCo2Gkm: Double? = null,
    // Hybrid/EV standard PID
    val hybridBatteryLifePct: Double? = null, // 0x5B
)

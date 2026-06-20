package me.crsib.freecartracker.domain.model

import kotlinx.serialization.Serializable

/**
 * How a vehicle is powered. Drives which entry forms are shown:
 * - [ICE] only burns fuel (hide charging)
 * - [EV] only charges (hide fuel)
 * - [HYBRID] burns fuel; battery is charged by the engine, not plugged in (hide charging)
 * - [REV] range-extended / plug-in hybrid: both fuel AND plug-in charging
 */
@Serializable
enum class Powertrain {
    ICE,
    HYBRID,
    REV,
    EV;

    val usesFuel: Boolean get() = this == ICE || this == HYBRID || this == REV
    val usesCharging: Boolean get() = this == REV || this == EV
}

@Serializable
enum class FuelType {
    PETROL,
    DIESEL,
    LPG,
    CNG,
    ETHANOL,
    OTHER,
}

@Serializable
enum class ChargeType {
    AC,
    DC,
}

/** Unit system used to display (and enter) values. Storage is always canonical SI-ish. */
@Serializable
enum class UnitSystem {
    METRIC, // km, litres, L/100km, kWh/100km
    IMPERIAL, // miles, US gallons, MPG, mi/kWh
}

@Serializable
enum class ExpenseCategory {
    SERVICE,
    CAR_WASH,
    ACCESSORY,
    FINANCING,
    INSURANCE,
    TAX,
    PARKING,
    TOLL,
    OTHER,
}

/** Recurrence hint for predictable costs (financing, insurance, tax). */
@Serializable
enum class Recurrence {
    NONE,
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY,
}

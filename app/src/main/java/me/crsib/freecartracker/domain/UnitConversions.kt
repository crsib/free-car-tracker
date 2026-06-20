package me.crsib.freecartracker.domain

import me.crsib.freecartracker.domain.model.UnitSystem

/**
 * Canonical storage units are metric: distance in km, volume in litres, energy in kWh.
 * These pure helpers convert to/from the units a [UnitSystem] displays. Unit-tested.
 */
object UnitConversions {
    const val KM_PER_MILE = 1.609344
    const val LITRES_PER_US_GALLON = 3.785411784

    // distance
    fun kmToDisplay(km: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) km / KM_PER_MILE else km

    fun displayToKm(value: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) value * KM_PER_MILE else value

    // volume
    fun litresToDisplay(litres: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) litres / LITRES_PER_US_GALLON else litres

    fun displayToLitres(value: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) value * LITRES_PER_US_GALLON else value

    /**
     * Convert a metric fuel economy (litres per 100 km) into the display unit:
     * litres/100km for metric, US MPG for imperial. Returns null for non-positive input.
     */
    fun fuelEconomyToDisplay(lPer100Km: Double?, system: UnitSystem): Double? {
        if (lPer100Km == null || lPer100Km <= 0.0) return null
        return if (system == UnitSystem.IMPERIAL) {
            // 235.214583 = (100 km in miles) * (litres per US gallon) unit bridge
            235.214583 / lPer100Km
        } else {
            lPer100Km
        }
    }

    /**
     * Convert metric electric economy (kWh per 100 km) into display:
     * kWh/100km for metric, miles per kWh for imperial.
     */
    fun electricEconomyToDisplay(kwhPer100Km: Double?, system: UnitSystem): Double? {
        if (kwhPer100Km == null || kwhPer100Km <= 0.0) return null
        return if (system == UnitSystem.IMPERIAL) {
            (100.0 / KM_PER_MILE) / kwhPer100Km
        } else {
            kwhPer100Km
        }
    }

    fun distanceUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "mi" else "km"
    fun volumeUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "gal" else "L"
    fun fuelEconomyUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "mpg" else "L/100km"
    fun electricEconomyUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "mi/kWh" else "kWh/100km"
}

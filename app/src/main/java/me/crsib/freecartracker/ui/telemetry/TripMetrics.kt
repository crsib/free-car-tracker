package me.crsib.freecartracker.ui.telemetry

/**
 * Average fuel usage (L/100km) inferred from a fuel-tank level drop over a trip — the fallback for
 * cars that don't report the fuel-rate PID, so `avgConsumptionL100` is otherwise null. Returns null
 * unless the tank actually dropped and capacity/distance are known. Shared by the trip list card
 * and the trip detail so both estimate it the same way.
 */
internal fun tankAvgUsageL100(
    startPct: Double?,
    endPct: Double?,
    tankCapacityL: Double?,
    distanceKm: Double?,
): Double? {
    if (startPct == null || endPct == null || tankCapacityL == null || distanceKm == null || distanceKm <= 0.0) {
        return null
    }
    val litres = (startPct - endPct) / 100.0 * tankCapacityL
    return if (litres > 0.0) litres / distanceKm * 100.0 else null
}

/**
 * Number of endpoint readings averaged to smooth fuel-level slosh. Kept in sync with the
 * `LIMIT` in [me.crsib.freecartracker.telemetry.data.dao.ObdSampleDao.fuelSpansByVehicle], so the
 * trip list and trip detail estimate the tank span from the same window.
 */
const val FUEL_ENDPOINT_WINDOW = 5

/**
 * Robust start/end fuel-tank level (%): the mean of the first and last [FUEL_ENDPOINT_WINDOW]
 * non-null readings rather than a single raw first/last sample. The fuel-level sender sloshes under
 * acceleration/braking, so a lone endpoint sample can be several percent off; averaging a short
 * window damps that noise. Returns null when there are no readings.
 */
internal fun robustFuelSpan(fuelLevels: List<Double>): Pair<Double, Double>? {
    if (fuelLevels.isEmpty()) return null
    val start = fuelLevels.take(FUEL_ENDPOINT_WINDOW).average()
    val end = fuelLevels.takeLast(FUEL_ENDPOINT_WINDOW).average()
    return start to end
}

/**
 * Average electrical consumption (kWh/100km) from the trip's net HV-pack energy over its distance.
 * `energyUsedKwh` is the signed integral of pack power, so regen (and, on a range-extender, engine
 * charging) is already netted out — this is net battery draw per 100 km. Null unless both inputs are
 * present, distance is positive, and the net draw is positive (a net-charging session isn't a
 * meaningful consumption figure).
 */
internal fun energyPer100Km(energyUsedKwh: Double?, distanceKm: Double?): Double? {
    if (energyUsedKwh == null || distanceKm == null || distanceKm <= 0.0) return null
    val per100 = energyUsedKwh / distanceKm * 100.0
    return if (per100 > 0.0) per100 else null
}

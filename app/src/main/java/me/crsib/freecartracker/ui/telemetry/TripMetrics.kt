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

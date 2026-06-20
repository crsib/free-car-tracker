package me.crsib.freecartracker.domain

/** A single fuel fill, in canonical units, fed to the economy calculator. */
data class FuelPoint(
    val odometerKm: Double?,
    val volumeL: Double,
    val isFullTank: Boolean,
    val dateMillis: Long,
)

/** A computed full-tank-to-full-tank fuel-economy interval. */
data class FuelSegment(
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val distanceKm: Double,
    val litres: Double,
    val endDateMillis: Long,
) {
    val litresPer100Km: Double get() = litres / distanceKm * 100.0
}

/**
 * Pure fuel/energy economy maths. Storage units are canonical (km, litres, kWh), so this
 * class is Android-free and unit-tested.
 */
object EconomyCalculator {

    /**
     * Compute economy intervals using the full-tank-to-full-tank method: between two full
     * fills, distance is the odometer delta and consumption is the sum of every fill made
     * *after* the opening full tank up to and including the closing one (partial fills
     * included). Fills without an odometer reading are ignored. Non-positive-distance
     * intervals are skipped.
     */
    fun fuelSegments(points: List<FuelPoint>): List<FuelSegment> {
        val ordered = points
            .filter { it.odometerKm != null }
            .sortedWith(compareBy({ it.odometerKm }, { it.dateMillis }))
        if (ordered.size < 2) return emptyList()

        val firstFull = ordered.indexOfFirst { it.isFullTank }
        if (firstFull < 0 || firstFull == ordered.lastIndex) return emptyList()

        val segments = mutableListOf<FuelSegment>()
        var anchor = firstFull
        var consumed = 0.0
        for (j in (firstFull + 1)..ordered.lastIndex) {
            consumed += ordered[j].volumeL
            if (ordered[j].isFullTank) {
                val startOdo = ordered[anchor].odometerKm!!
                val endOdo = ordered[j].odometerKm!!
                val distance = endOdo - startOdo
                if (distance > 0.0) {
                    segments += FuelSegment(
                        startOdometerKm = startOdo,
                        endOdometerKm = endOdo,
                        distanceKm = distance,
                        litres = consumed,
                        endDateMillis = ordered[j].dateMillis,
                    )
                }
                anchor = j
                consumed = 0.0
            }
        }
        return segments
    }

    /** Economy (L/100km) of the most recent completed full-to-full interval, if any. */
    fun latestFuelEconomyL100(points: List<FuelPoint>): Double? =
        fuelSegments(points).maxByOrNull { it.endDateMillis }?.litresPer100Km

    /**
     * Distance covered by a set of odometer readings (km): the span between the smallest and
     * largest. Returns null when fewer than two readings are available.
     */
    fun distanceSpanKm(odometersKm: List<Double>): Double? {
        if (odometersKm.size < 2) return null
        val min = odometersKm.min()
        val max = odometersKm.max()
        return (max - min).takeIf { it > 0.0 }
    }

    /** Generic "quantity per 100 km" (fuel or energy). Null if inputs are non-positive. */
    fun per100Km(quantity: Double, distanceKm: Double?): Double? {
        if (distanceKm == null || distanceKm <= 0.0 || quantity <= 0.0) return null
        return quantity / distanceKm * 100.0
    }

    /** Blended energy economy (kWh/100km) over a distance. Null if inputs are non-positive. */
    fun electricEconomyKwh100(totalKwh: Double, distanceKm: Double?): Double? =
        per100Km(totalKwh, distanceKm)

    /** Cost (in minor units) per km over a distance. Null if distance is unusable. */
    fun costPerKmMinor(totalCostMinor: Long, distanceKm: Double?): Double? {
        if (distanceKm == null || distanceKm <= 0.0) return null
        return totalCostMinor / distanceKm
    }
}

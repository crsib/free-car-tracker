package me.crsib.freecartracker.domain

import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.domain.model.FuelType

/**
 * An energy source whose running cost is tracked separately: one fuel type, or electricity.
 * [fuelType] is null for electricity and for fuel entries logged without a type.
 */
data class EnergySource(val kind: EntryKind, val fuelType: FuelType?) {
    companion object {
        val ELECTRICITY = EnergySource(EntryKind.CHARGE, null)
        fun fuel(type: FuelType?) = EnergySource(EntryKind.FUEL, type)
    }
}

/** One purchase of energy, reduced to what the cost breakdown needs. */
data class EnergyPurchase(
    val source: EnergySource,
    val costMinor: Long,
    /** Litres for a fuel purchase, kWh for a charge. */
    val quantity: Double,
)

/** What one energy source cost over a window, and its share of the per-km running cost. */
data class EnergyCostShare(
    val source: EnergySource,
    val spentMinor: Long,
    /** Litres for a fuel source, kWh for electricity. */
    val quantity: Double,
    /** Minor units per km, or null when the distance is unusable. */
    val costPerKmMinor: Double?,
)

/**
 * Splits energy spend into per-source running costs. Pure and Android-free, so unit-tested.
 *
 * Every share is that source's spend over the *whole* distance driven in the window, not over
 * some distance attributed to it: a car cannot say which kilometres ran on LPG and which on
 * petrol. Read a share as "what this source adds to each kilometre" — the shares therefore sum
 * to [totalCostPerKmMinor].
 */
object EnergyCostCalculator {

    /** Per-source shares over [distanceKm], biggest spender first. Sources with no spend and no quantity are dropped. */
    fun breakdown(purchases: List<EnergyPurchase>, distanceKm: Double?): List<EnergyCostShare> {
        val bySource = LinkedHashMap<EnergySource, EnergyCostShare>()
        purchases.forEach { p ->
            val acc = bySource[p.source]
            bySource[p.source] = EnergyCostShare(
                source = p.source,
                spentMinor = (acc?.spentMinor ?: 0L) + p.costMinor,
                quantity = (acc?.quantity ?: 0.0) + p.quantity,
                costPerKmMinor = null,
            )
        }
        return bySource.values
            .filter { it.spentMinor != 0L || it.quantity > 0.0 }
            .map { it.copy(costPerKmMinor = EconomyCalculator.costPerKmMinor(it.spentMinor, distanceKm)) }
            .sortedByDescending { it.spentMinor }
    }

    /**
     * Combined cost per km of every energy source (fuel + electricity), excluding other expenses.
     * Null when nothing was bought — that is "unknown", not "free".
     */
    fun totalCostPerKmMinor(purchases: List<EnergyPurchase>, distanceKm: Double?): Double? {
        if (purchases.isEmpty()) return null
        return EconomyCalculator.costPerKmMinor(purchases.sumOf { it.costMinor }, distanceKm)
    }
}

package me.crsib.freecartracker.domain

import me.crsib.freecartracker.domain.model.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyCostCalculatorTest {

    private fun petrol(costMinor: Long, litres: Double) =
        EnergyPurchase(EnergySource.fuel(FuelType.PETROL), costMinor, litres)

    private fun lpg(costMinor: Long, litres: Double) =
        EnergyPurchase(EnergySource.fuel(FuelType.LPG), costMinor, litres)

    private fun charge(costMinor: Long, kwh: Double) =
        EnergyPurchase(EnergySource.ELECTRICITY, costMinor, kwh)

    @Test
    fun `groups purchases by source and sums spend and quantity`() {
        val shares = EnergyCostCalculator.breakdown(
            listOf(petrol(10_000, 40.0), petrol(5_000, 20.0), charge(2_000, 30.0)),
            distanceKm = 1_000.0,
        )

        assertEquals(2, shares.size)
        val fuel = shares.first { it.source == EnergySource.fuel(FuelType.PETROL) }
        assertEquals(15_000L, fuel.spentMinor)
        assertEquals(60.0, fuel.quantity, 1e-9)
        assertEquals(15.0, fuel.costPerKmMinor!!, 1e-9)
    }

    @Test
    fun `shares sum to the combined energy cost per km`() {
        val purchases = listOf(petrol(10_000, 40.0), lpg(4_000, 50.0), charge(2_000, 30.0))
        val shares = EnergyCostCalculator.breakdown(purchases, distanceKm = 800.0)

        val summed = shares.sumOf { it.costPerKmMinor!! }
        assertEquals(EnergyCostCalculator.totalCostPerKmMinor(purchases, 800.0)!!, summed, 1e-9)
    }

    @Test
    fun `orders sources by spend, biggest first`() {
        val shares = EnergyCostCalculator.breakdown(
            listOf(charge(2_000, 30.0), petrol(10_000, 40.0), lpg(4_000, 50.0)),
            distanceKm = 500.0,
        )

        assertEquals(
            listOf(
                EnergySource.fuel(FuelType.PETROL),
                EnergySource.fuel(FuelType.LPG),
                EnergySource.ELECTRICITY,
            ),
            shares.map { it.source },
        )
    }

    @Test
    fun `untyped fuel is its own source`() {
        val shares = EnergyCostCalculator.breakdown(
            listOf(petrol(1_000, 10.0), EnergyPurchase(EnergySource.fuel(null), 3_000, 20.0)),
            distanceKm = 100.0,
        )

        assertEquals(2, shares.size)
        assertTrue(shares.any { it.source.fuelType == null && it.source.kind == me.crsib.freecartracker.domain.model.EntryKind.FUEL })
    }

    @Test
    fun `free charging still appears, with a zero per-km share`() {
        val shares = EnergyCostCalculator.breakdown(listOf(charge(0, 12.0)), distanceKm = 100.0)

        assertEquals(1, shares.size)
        assertEquals(12.0, shares[0].quantity, 1e-9)
        assertEquals(0.0, shares[0].costPerKmMinor!!, 1e-9)
    }

    @Test
    fun `unusable distance leaves per-km costs null`() {
        val shares = EnergyCostCalculator.breakdown(listOf(petrol(10_000, 40.0)), distanceKm = null)

        assertEquals(1, shares.size)
        assertNull(shares[0].costPerKmMinor)
        assertNull(EnergyCostCalculator.totalCostPerKmMinor(listOf(petrol(10_000, 40.0)), 0.0))
    }

    @Test
    fun `no purchases yields no shares`() {
        assertEquals(emptyList<EnergyCostShare>(), EnergyCostCalculator.breakdown(emptyList(), 100.0))
        assertNull(EnergyCostCalculator.totalCostPerKmMinor(emptyList(), 100.0))
    }
}

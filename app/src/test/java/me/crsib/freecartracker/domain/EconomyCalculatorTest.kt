package me.crsib.freecartracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EconomyCalculatorTest {

    private fun fill(odo: Double?, litres: Double, full: Boolean, date: Long = odo?.toLong() ?: 0L) =
        FuelPoint(odometerKm = odo, volumeL = litres, isFullTank = full, dateMillis = date)

    @Test
    fun `single full-to-full segment computes L per 100km`() {
        // Fill to full at 1000 km, then fill 40 L to full at 1500 km -> 40 L over 500 km = 8.0
        val points = listOf(
            fill(1000.0, 45.0, full = true),
            fill(1500.0, 40.0, full = true),
        )
        val segments = EconomyCalculator.fuelSegments(points)
        assertEquals(1, segments.size)
        assertEquals(500.0, segments[0].distanceKm, 1e-9)
        assertEquals(40.0, segments[0].litres, 1e-9)
        assertEquals(8.0, segments[0].litresPer100Km, 1e-9)
    }

    @Test
    fun `partial fills between full tanks are summed into consumption`() {
        // full@1000, partial 20L@1200, full 30L@1500 -> consumed 50 L over 500 km = 10.0
        val points = listOf(
            fill(1000.0, 45.0, full = true),
            fill(1200.0, 20.0, full = false),
            fill(1500.0, 30.0, full = true),
        )
        val segments = EconomyCalculator.fuelSegments(points)
        assertEquals(1, segments.size)
        assertEquals(50.0, segments[0].litres, 1e-9)
        assertEquals(10.0, segments[0].litresPer100Km, 1e-9)
    }

    @Test
    fun `opening full tank volume is excluded from the segment`() {
        // The 45 L put in at the first full tank is NOT counted toward the segment.
        val points = listOf(
            fill(1000.0, 45.0, full = true),
            fill(1400.0, 32.0, full = true),
        )
        val segments = EconomyCalculator.fuelSegments(points)
        assertEquals(32.0, segments[0].litres, 1e-9)
    }

    @Test
    fun `multiple segments and latest economy picks most recent`() {
        val points = listOf(
            fill(0.0, 40.0, full = true, date = 1),
            fill(500.0, 40.0, full = true, date = 2), // 40/500*100 = 8.0
            fill(1000.0, 25.0, full = true, date = 3), // 25/500*100 = 5.0
        )
        val segments = EconomyCalculator.fuelSegments(points)
        assertEquals(2, segments.size)
        assertEquals(5.0, EconomyCalculator.latestFuelEconomyL100(points)!!, 1e-9)
    }

    @Test
    fun `fills without odometer are ignored`() {
        val points = listOf(
            fill(1000.0, 40.0, full = true),
            fill(null, 15.0, full = false),
            fill(1500.0, 30.0, full = true),
        )
        val segments = EconomyCalculator.fuelSegments(points)
        // The null-odometer 15 L fill is dropped, so consumption is just the closing 30 L.
        assertEquals(1, segments.size)
        assertEquals(30.0, segments[0].litres, 1e-9)
    }

    @Test
    fun `no full tanks yields no segments`() {
        val points = listOf(
            fill(1000.0, 20.0, full = false),
            fill(1200.0, 20.0, full = false),
        )
        assertTrue(EconomyCalculator.fuelSegments(points).isEmpty())
        assertNull(EconomyCalculator.latestFuelEconomyL100(points))
    }

    @Test
    fun `zero or negative distance segment is skipped`() {
        val points = listOf(
            fill(1000.0, 40.0, full = true),
            fill(1000.0, 40.0, full = true), // same odometer -> distance 0, skipped
        )
        assertTrue(EconomyCalculator.fuelSegments(points).isEmpty())
    }

    @Test
    fun `distance span returns max minus min`() {
        assertEquals(800.0, EconomyCalculator.distanceSpanKm(listOf(1000.0, 1800.0, 1200.0))!!, 1e-9)
        assertNull(EconomyCalculator.distanceSpanKm(listOf(1000.0)))
    }

    @Test
    fun `electric economy computes kWh per 100km`() {
        assertEquals(18.0, EconomyCalculator.electricEconomyKwh100(36.0, 200.0)!!, 1e-9)
        assertNull(EconomyCalculator.electricEconomyKwh100(0.0, 200.0))
        assertNull(EconomyCalculator.electricEconomyKwh100(10.0, null))
    }

    @Test
    fun `cost per km divides minor cost by distance`() {
        assertEquals(25.0, EconomyCalculator.costPerKmMinor(5000, 200.0)!!, 1e-9)
        assertNull(EconomyCalculator.costPerKmMinor(5000, null))
    }
}

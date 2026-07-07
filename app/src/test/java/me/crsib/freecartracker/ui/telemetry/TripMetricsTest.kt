package me.crsib.freecartracker.ui.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit coverage for the per-trip metric derivations shown on the trip detail. */
class TripMetricsTest {

    @Test
    fun energyPer100Km_computesFromNetPackEnergyAndDistance() {
        // 9 kWh over 50 km -> 18 kWh/100km.
        assertEquals(18.0, energyPer100Km(9.0, 50.0)!!, 1e-9)
    }

    @Test
    fun energyPer100Km_nullWhenInputsMissingOrDistanceZero() {
        assertNull(energyPer100Km(null, 50.0))
        assertNull(energyPer100Km(9.0, null))
        assertNull(energyPer100Km(9.0, 0.0))
        assertNull(energyPer100Km(9.0, -5.0))
    }

    @Test
    fun energyPer100Km_nullWhenNetEnergyIsNonPositive() {
        // A net-charging session (regen/engine charge exceeded draw) isn't a consumption figure.
        assertNull(energyPer100Km(-2.0, 50.0))
        assertNull(energyPer100Km(0.0, 50.0))
    }

    @Test
    fun robustFuelSpan_nullWhenNoReadings() {
        assertNull(robustFuelSpan(emptyList()))
    }

    @Test
    fun robustFuelSpan_averagesTheFirstAndLastWindow() {
        // 12 readings: a slosh spike at each end is damped by averaging the 5-sample window.
        val levels = listOf(80.0, 79.0, 78.0, 79.0, 80.0, 70.0, 65.0, 60.0, 61.0, 60.0, 59.0, 60.0)
        val (start, end) = robustFuelSpan(levels)!!
        assertEquals(listOf(80.0, 79.0, 78.0, 79.0, 80.0).average(), start, 1e-9) // 79.2
        assertEquals(listOf(60.0, 61.0, 60.0, 59.0, 60.0).average(), end, 1e-9)   // 60.0
    }

    @Test
    fun robustFuelSpan_windowsOverlapWhenTooFewReadings() {
        // Fewer than 2×window readings: the first- and last-5 windows overlap, so start ≈ end and
        // the inferred drop collapses toward zero — a deliberately conservative result for a trip
        // too short to smooth (it just yields no tank-drop estimate rather than a noisy one).
        val (start, end) = robustFuelSpan(listOf(75.0, 60.0))!!
        assertEquals(67.5, start, 1e-9)
        assertEquals(67.5, end, 1e-9)
    }
}

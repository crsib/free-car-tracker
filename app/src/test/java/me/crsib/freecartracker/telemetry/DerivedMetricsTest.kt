package me.crsib.freecartracker.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedMetricsTest {

    @Test
    fun l100FromFuelRate_basic() {
        // 5 L/h at 50 km/h -> 10 L/100km
        assertEquals(10.0, DerivedMetrics.l100FromFuelRate(5.0, 50.0)!!, 1e-6)
    }

    @Test
    fun l100FromFuelRate_nullWhenStationaryOrMissing() {
        assertNull(DerivedMetrics.l100FromFuelRate(5.0, 0.0))
        assertNull(DerivedMetrics.l100FromFuelRate(null, 50.0))
        assertNull(DerivedMetrics.l100FromFuelRate(5.0, null))
    }

    @Test
    fun l100FromMaf_isPositiveAndReasonable() {
        val l100 = DerivedMetrics.l100FromMaf(5.0, 50.0)!!
        assertTrue("expected a small positive consumption, got $l100", l100 in 1.0..10.0)
    }

    @Test
    fun l100_prefersFuelRateOverMaf() {
        // Fuel rate present -> use it (10.0), ignoring MAF.
        assertEquals(10.0, DerivedMetrics.l100(5.0, 999.0, 50.0)!!, 1e-6)
        // Fuel rate absent -> fall back to MAF (non-null).
        assertTrue(DerivedMetrics.l100(null, 5.0, 50.0)!! > 0.0)
    }

    @Test
    fun co2FromL100() {
        assertEquals(239.2, DerivedMetrics.co2GkmFromL100(10.0)!!, 1e-6)
        assertNull(DerivedMetrics.co2GkmFromL100(null))
    }

    @Test
    fun haversine_oneDegreeLongitudeAtEquator() {
        // ~111.19 km per degree of longitude at the equator
        val km = DerivedMetrics.haversineKm(0.0, 0.0, 0.0, 1.0)
        assertEquals(111.19, km, 0.5)
    }
}

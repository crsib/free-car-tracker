package me.crsib.freecartracker.telemetry

import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAggregatorTest {

    private fun gps(tsSec: Long, lat: Double, lon: Double, speedKmh: Double?) =
        GpsPoint(
            tripId = 1, timestampMillis = tsSec * 1000, latitude = lat, longitude = lon,
            altitudeM = null, speedKmh = speedKmh, bearingDeg = null, accuracyM = null, provider = "gps",
        )

    private fun obd(tsSec: Long, speedKmh: Double?, fuelRateLph: Double? = null) =
        ObdSample(tripId = 1, timestampMillis = tsSec * 1000, speedKmh = speedKmh, fuelRateLph = fuelRateLph)

    @Test
    fun gpsTrip_distanceAndSpeeds() {
        val gps = listOf(
            gps(0, 0.0, 0.0, 0.0),
            gps(10, 0.0, 0.001, 36.0),
            gps(20, 0.0, 0.002, 40.0),
        )
        val agg = TripAggregator.aggregate(0, 20_000, gps, emptyList())
        // ~0.222 km total (two ~0.111 km hops)
        assertEquals(0.222, agg.distanceKm!!, 0.02)
        assertEquals(40.0, agg.maxSpeedKmh!!, 1e-6)
        assertEquals(20L, agg.durationSec)
        assertEquals(3, agg.gpsPointCount)
        assertEquals(0, agg.obdSampleCount)
        assertNull("no fuel-rate samples -> null consumption", agg.fuelUsedL)
    }

    @Test
    fun obdOnlyTrip_distanceFromSpeedIntegration() {
        // 36 km/h constant for 100 s -> 1.0 km
        val obd = listOf(
            obd(0, 36.0),
            obd(50, 36.0),
            obd(100, 36.0),
        )
        val agg = TripAggregator.aggregate(0, 100_000, emptyList(), obd)
        assertEquals(1.0, agg.distanceKm!!, 0.01)
        assertEquals(3, agg.obdSampleCount)
        assertEquals(0, agg.gpsPointCount)
    }

    @Test
    fun movingTime_excludesIdle() {
        // First 10 s idling (0 km/h), next 10 s moving (50 km/h)
        val gps = listOf(
            gps(0, 0.0, 0.0, 0.0),
            gps(10, 0.0, 0.0, 0.0),
            gps(20, 0.0, 0.001, 50.0),
        )
        val agg = TripAggregator.aggregate(0, 20_000, gps, emptyList())
        assertEquals(10L, agg.movingSec)
    }

    @Test
    fun fuelConsumption_fromFuelRateIntegration() {
        // 6 L/h for 3600 s -> 6 L; distance from speed: 60 km/h * 1 h = 60 km -> 10 L/100km
        val obd = listOf(
            obd(0, 60.0, 6.0),
            obd(1800, 60.0, 6.0),
            obd(3600, 60.0, 6.0),
        )
        val agg = TripAggregator.aggregate(0, 3_600_000, emptyList(), obd)
        assertNotNull(agg.fuelUsedL)
        assertEquals(6.0, agg.fuelUsedL!!, 0.01)
        assertEquals(10.0, agg.avgConsumptionL100!!, 0.2)
    }

    private fun obdPower(tsSec: Long, powerKw: Double) =
        ObdSample(tripId = 1, timestampMillis = tsSec * 1000, hvBatteryPowerKw = powerKw)

    @Test
    fun energy_fromHvPowerIntegration() {
        // 20 kW constant for 3600 s -> 20 kWh
        val obd = listOf(obdPower(0, 20.0), obdPower(1800, 20.0), obdPower(3600, 20.0))
        val agg = TripAggregator.aggregate(0, 3_600_000, emptyList(), obd)
        assertNotNull(agg.energyUsedKwh)
        assertEquals(20.0, agg.energyUsedKwh!!, 0.01)
    }

    @Test
    fun energy_regenIsNettedOut() {
        // 10 kW draw then 10 kW regen over the same span -> net ~0 kWh (signed power).
        val obd = listOf(obdPower(0, 10.0), obdPower(3600, -10.0))
        val agg = TripAggregator.aggregate(0, 3_600_000, emptyList(), obd)
        assertEquals(0.0, agg.energyUsedKwh!!, 0.01)
    }

    @Test
    fun energy_nullWithoutPowerSamples() {
        val obd = listOf(obd(0, 60.0, 6.0), obd(3600, 60.0, 6.0))
        assertNull(TripAggregator.aggregate(0, 3_600_000, emptyList(), obd).energyUsedKwh)
    }

    @Test
    fun emptyTrip_yieldsNulls() {
        val agg = TripAggregator.aggregate(0, 5_000, emptyList(), emptyList())
        assertNull(agg.distanceKm)
        assertNull(agg.avgSpeedKmh)
        assertEquals(5L, agg.durationSec)
        assertTrue(agg.obdSampleCount == 0 && agg.gpsPointCount == 0)
    }
}

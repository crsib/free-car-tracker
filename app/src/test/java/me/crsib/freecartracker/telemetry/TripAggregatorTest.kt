package me.crsib.freecartracker.telemetry

import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAggregatorTest {

    private fun gps(tsSec: Long, lat: Double, lon: Double, speedKmh: Double?, accuracyM: Float? = null) =
        GpsPoint(
            tripId = 1, timestampMillis = tsSec * 1000, latitude = lat, longitude = lon,
            altitudeM = null, speedKmh = speedKmh, bearingDeg = null, accuracyM = accuracyM, provider = "gps",
        )

    private fun obd(
        tsSec: Long,
        speedKmh: Double?,
        fuelRateLph: Double? = null,
        odometerKm: Double? = null,
        hvBatteryPowerKw: Double? = null,
    ) =
        ObdSample(
            tripId = 1, timestampMillis = tsSec * 1000, speedKmh = speedKmh,
            fuelRateLph = fuelRateLph, odometerKm = odometerKm, hvBatteryPowerKw = hvBatteryPowerKw,
        )

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
    fun stationaryJitter_yieldsNearZeroDistance() {
        // A phone sitting still next to the car: fixes wander a few metres each second (≈5 m hops,
        // 15 m accuracy) at a walking-or-less GPS speed. Raw haversine would sum this into a phantom
        // "trip"; the gate must read it as ~0 so the session is later discarded as trivial.
        val jitter = listOf(
            gps(0, 0.0, 0.00000, 0.4, accuracyM = 15f),
            gps(1, 0.00004, 0.00003, 0.6, accuracyM = 15f),
            gps(2, -0.00003, 0.00005, 0.3, accuracyM = 15f),
            gps(3, 0.00002, -0.00004, 0.5, accuracyM = 15f),
            gps(4, 0.00005, 0.00001, 0.2, accuracyM = 15f),
        )
        val agg = TripAggregator.aggregate(0, 4_000, jitter, emptyList())
        assertEquals(0.0, agg.distanceKm!!, 1e-9)
    }

    @Test
    fun coarseFixes_areDroppedFromDistance() {
        // Two very inaccurate fixes far apart must not fabricate distance.
        val gps = listOf(
            gps(0, 0.0, 0.0, 40.0, accuracyM = 200f),
            gps(10, 0.0, 0.01, 40.0, accuracyM = 200f),
        )
        assertNull(TripAggregator.aggregate(0, 10_000, gps, emptyList()).distanceKm)
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
    fun odometer_preferredOverGpsForDistance() {
        // Odometer says 12.0 km driven; a sparse/garbage GPS track would say otherwise. Odometer wins.
        val gps = listOf(
            gps(0, 0.0, 0.0, 30.0),
            gps(60, 0.0, 0.001, 30.0), // ~0.111 km — clearly wrong for the real drive
        )
        val obd = listOf(
            obd(0, 30.0, odometerKm = 45_000.0),
            obd(60, 30.0, odometerKm = 45_012.0),
        )
        val agg = TripAggregator.aggregate(0, 60_000, gps, obd)
        assertEquals(12.0, agg.distanceKm!!, 1e-6)
    }

    @Test
    fun odometer_derivesFuelEconomyEvenWithoutGps() {
        // Headless trip (no GPS): 6 L/h for 3600 s -> 6 L, 60 km by odometer -> 10 L/100km.
        val obd = listOf(
            obd(0, 60.0, fuelRateLph = 6.0, odometerKm = 1000.0),
            obd(1800, 60.0, fuelRateLph = 6.0, odometerKm = 1030.0),
            obd(3600, 60.0, fuelRateLph = 6.0, odometerKm = 1060.0),
        )
        val agg = TripAggregator.aggregate(0, 3_600_000, emptyList(), obd)
        assertEquals(60.0, agg.distanceKm!!, 1e-6)
        assertEquals(10.0, agg.avgConsumptionL100!!, 1e-6)
    }

    @Test
    fun odometer_implausibleDeltaFallsBackToSpeed() {
        // A rollover/garbage odometer delta is ignored; distance falls back to speed integration.
        val obd = listOf(
            obd(0, 36.0, odometerKm = 90_000.0),
            obd(50, 36.0, odometerKm = 0.0),
            obd(100, 36.0, odometerKm = 1.0),
        )
        val agg = TripAggregator.aggregate(0, 100_000, emptyList(), obd)
        assertEquals(1.0, agg.distanceKm!!, 0.01) // 36 km/h for 100 s
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

    @Test
    fun fuelRate_singleGarbageSampleIsDroppedNotIntegrated() {
        // An ELM327 that mis-frames one response can return a value orders of magnitude out.
        // Integrating it produced a recorded 5.97e14 litres on two real trips. The bad sample is
        // dropped and the surrounding, sane ones still give the right answer: 6 L/h for 3600 s.
        val obd = listOf(
            obd(0, 60.0, fuelRateLph = 6.0),
            obd(1800, 60.0, fuelRateLph = 3.2e15),
            obd(3600, 60.0, fuelRateLph = 6.0),
        )
        val agg = TripAggregator.aggregate(0, 3_600_000, emptyList(), obd)
        assertEquals(6.0, agg.fuelUsedL!!, 1e-6)
    }

    @Test
    fun fuelRate_implausibleTotalReportsNothing() {
        // Every sample individually passes the per-sample bound, but sustained maximum flow for
        // days cannot have happened. Reporting nothing beats reporting a fiction.
        val obd = (0..20).map { obd(it * 36_000L, 60.0, fuelRateLph = 140.0) }
        val agg = TripAggregator.aggregate(0, 720_000_000, emptyList(), obd)
        assertNull("an impossible total should be discarded", agg.fuelUsedL)
        assertNull("and so should the economy derived from it", agg.avgConsumptionL100)
    }

    @Test
    fun fuelRate_nonFiniteSamplesAreIgnored() {
        val obd = listOf(
            obd(0, 60.0, fuelRateLph = 6.0),
            obd(1800, 60.0, fuelRateLph = Double.NaN),
            obd(2700, 60.0, fuelRateLph = Double.POSITIVE_INFINITY),
            obd(3600, 60.0, fuelRateLph = 6.0),
        )
        val agg = TripAggregator.aggregate(0, 3_600_000, emptyList(), obd)
        assertEquals(6.0, agg.fuelUsedL!!, 1e-6)
    }

    @Test
    fun speed_garbageSampleDoesNotBecomeTheTripMaximum() {
        // maxSpeedKmh is shown straight on the trip screen, so one misframed reading would be
        // permanently visible as a 9000 km/h top speed.
        val obd = listOf(
            obd(0, 40.0),
            obd(10, 9_000.0),
            obd(20, 60.0),
        )
        val agg = TripAggregator.aggregate(0, 20_000, emptyList(), obd)
        assertEquals(60.0, agg.maxSpeedKmh!!, 1e-6)
    }

    @Test
    fun batteryPower_garbageSampleIsDroppedFromTheEnergyIntegral() {
        // 10 kW for 3600 s is 10 kWh; the spike in the middle must not reach the total.
        val obd = listOf(
            obd(0, 60.0, hvBatteryPowerKw = 10.0),
            obd(1800, 60.0, hvBatteryPowerKw = 5.0e9),
            obd(3600, 60.0, hvBatteryPowerKw = 10.0),
        )
        val agg = TripAggregator.aggregate(0, 3_600_000, emptyList(), obd)
        assertEquals(10.0, agg.energyUsedKwh!!, 1e-6)
    }

    @Test
    fun guards_leaveLegitimateExtremesAlone() {
        // The bounds must not clip real driving: a thirsty engine at 90 L/h, an autobahn run at
        // 250 km/h, and hard regen at -120 kW are all plausible and must survive.
        val obd = listOf(
            obd(0, 250.0, fuelRateLph = 90.0, hvBatteryPowerKw = -120.0),
            obd(3600, 250.0, fuelRateLph = 90.0, hvBatteryPowerKw = -120.0),
        )
        val agg = TripAggregator.aggregate(0, 3_600_000, emptyList(), obd)
        assertEquals(90.0, agg.fuelUsedL!!, 1e-6)
        assertEquals(250.0, agg.maxSpeedKmh!!, 1e-6)
        assertEquals(-120.0, agg.energyUsedKwh!!, 1e-6)
    }
}

package me.crsib.freecartracker.telemetry

import me.crsib.freecartracker.telemetry.data.TripAggregates
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdSample

/**
 * Computes a trip's summary aggregates from its recorded samples. Pure (no Android/DB), so it
 * is unit-testable. Distance prefers the GPS track (haversine); if there are too few GPS fixes
 * it falls back to integrating OBD speed over time. GPS-only and OBD-only trips both work.
 */
object TripAggregator {

    /** Speed (km/h) below which the car is considered idling, not moving. */
    private const val MOVING_THRESHOLD_KMH = 3.0

    fun aggregate(
        startedAtMillis: Long,
        endedAtMillis: Long,
        gps: List<GpsPoint>,
        obd: List<ObdSample>,
    ): TripAggregates {
        val durationSec = ((endedAtMillis - startedAtMillis).coerceAtLeast(0)) / 1000

        val gpsDistance = gpsDistanceKm(gps)
        val distanceKm = when {
            gpsDistance != null -> gpsDistance
            else -> obdDistanceKm(obd)
        }

        val speeds = when {
            gps.count { it.speedKmh != null } >= 2 -> gps.mapNotNull { it.speedKmh }
            else -> obd.mapNotNull { it.speedKmh }
        }
        val avgSpeed = speeds.takeIf { it.isNotEmpty() }?.average()
        val maxSpeed = speeds.maxOrNull()

        val movingSec = movingSeconds(gps, obd)
        val fuelUsedL = fuelUsedLitres(obd)
        val energyUsedKwh = batteryEnergyKwh(obd)
        val avgConsumption = if (fuelUsedL != null && distanceKm != null && distanceKm > 0) {
            fuelUsedL / distanceKm * 100.0
        } else {
            null
        }

        return TripAggregates(
            distanceKm = distanceKm,
            durationSec = durationSec,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed,
            movingSec = movingSec,
            fuelUsedL = fuelUsedL,
            energyUsedKwh = energyUsedKwh,
            avgConsumptionL100 = avgConsumption,
            obdSampleCount = obd.size,
            gpsPointCount = gps.size,
        )
    }

    private fun gpsDistanceKm(gps: List<GpsPoint>): Double? {
        if (gps.size < 2) return null
        var total = 0.0
        for (i in 1 until gps.size) {
            val a = gps[i - 1]
            val b = gps[i]
            total += DerivedMetrics.haversineKm(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return total
    }

    /** Integrate OBD speed (km/h) over sample time gaps as a distance fallback. */
    private fun obdDistanceKm(obd: List<ObdSample>): Double? {
        val withSpeed = obd.filter { it.speedKmh != null }
        if (withSpeed.size < 2) return null
        var km = 0.0
        for (i in 1 until withSpeed.size) {
            val dtHours = (withSpeed[i].timestampMillis - withSpeed[i - 1].timestampMillis) / 3_600_000.0
            if (dtHours <= 0) continue
            val avg = (withSpeed[i].speedKmh!! + withSpeed[i - 1].speedKmh!!) / 2.0
            km += avg * dtHours
        }
        return km
    }

    private fun movingSeconds(gps: List<GpsPoint>, obd: List<ObdSample>): Long? {
        val series: List<Pair<Long, Double>> = when {
            gps.count { it.speedKmh != null } >= 2 ->
                gps.mapNotNull { p -> p.speedKmh?.let { p.timestampMillis to it } }
            obd.count { it.speedKmh != null } >= 2 ->
                obd.mapNotNull { s -> s.speedKmh?.let { s.timestampMillis to it } }
            else -> return null
        }
        var movingMs = 0L
        for (i in 1 until series.size) {
            val dt = series[i].first - series[i - 1].first
            if (dt <= 0) continue
            val avg = (series[i].second + series[i - 1].second) / 2.0
            if (avg >= MOVING_THRESHOLD_KMH) movingMs += dt
        }
        return movingMs / 1000
    }

    /**
     * Integrate HV battery power (kW) over time into net energy (kWh). Power is signed (negative =
     * regen/charge), so the result is net traction energy: regen during the trip reduces it. Null
     * when there aren't enough power samples (e.g. an ICE car, or before 0x9A is read).
     */
    private fun batteryEnergyKwh(obd: List<ObdSample>): Double? {
        val withPower = obd.filter { it.hvBatteryPowerKw != null }
        if (withPower.size < 2) return null
        var kwh = 0.0
        for (i in 1 until withPower.size) {
            val dtHours = (withPower[i].timestampMillis - withPower[i - 1].timestampMillis) / 3_600_000.0
            if (dtHours <= 0) continue
            val avgKw = (withPower[i].hvBatteryPowerKw!! + withPower[i - 1].hvBatteryPowerKw!!) / 2.0
            kwh += avgKw * dtHours
        }
        return kwh
    }

    /** Integrate fuel rate (L/h) over time, in litres. Null if no fuel-rate samples. */
    private fun fuelUsedLitres(obd: List<ObdSample>): Double? {
        val withRate = obd.filter { it.fuelRateLph != null }
        if (withRate.size < 2) return null
        var litres = 0.0
        for (i in 1 until withRate.size) {
            val dtHours = (withRate[i].timestampMillis - withRate[i - 1].timestampMillis) / 3_600_000.0
            if (dtHours <= 0) continue
            val avgRate = (withRate[i].fuelRateLph!! + withRate[i - 1].fuelRateLph!!) / 2.0
            litres += avgRate * dtHours
        }
        return litres
    }
}

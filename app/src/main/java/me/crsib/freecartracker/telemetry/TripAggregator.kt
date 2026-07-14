package me.crsib.freecartracker.telemetry

import me.crsib.freecartracker.telemetry.data.TripAggregates
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdSample

/**
 * Computes a trip's summary aggregates from its recorded samples. Pure (no Android/DB), so it
 * is unit-testable. Distance prefers the vehicle odometer (the most accurate source, and the one
 * that keeps working when GPS is unavailable); it falls back to the GPS track (haversine), then to
 * integrating OBD speed over time. GPS-only and OBD-only trips both work.
 */
object TripAggregator {

    /** Speed (km/h) below which the car is considered idling, not moving. */
    private const val MOVING_THRESHOLD_KMH = 3.0

    /**
     * Upper bound on a single trip's odometer delta (km). Guards against an odometer PID that
     * rolls over or reports a garbage value: a delta above this is treated as implausible and we
     * fall back to GPS/OBD-speed distance instead.
     */
    private const val MAX_PLAUSIBLE_ODOMETER_DELTA_KM = 5_000.0

    /** GPS fixes worse than this (metres) are too coarse to trust for distance and are dropped. */
    private const val GPS_MAX_ACCURACY_M = 50.0

    /** A hop shorter than this (metres) is below the GPS noise floor even for an accurate fix. */
    private const val GPS_MIN_STEP_M = 8.0

    fun aggregate(
        startedAtMillis: Long,
        endedAtMillis: Long,
        gps: List<GpsPoint>,
        obd: List<ObdSample>,
    ): TripAggregates {
        val durationSec = ((endedAtMillis - startedAtMillis).coerceAtLeast(0)) / 1000

        // Odometer first: an absolute, drift-free reading that (unlike GPS) is recorded even when
        // the trip runs headless without a location fix. GPS haversine and OBD-speed integration
        // are fallbacks for trips where the odometer PID isn't available.
        val distanceKm = odometerDistanceKm(obd)
            ?: gpsDistanceKm(gps)
            ?: obdDistanceKm(obd)

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

    /**
     * Distance from the vehicle odometer: last minus first reading. The odometer is monotonic, so
     * the delta is the exact ground distance driven. Returns null when there aren't two readings,
     * the delta is non-positive (sub-1-km trip on a coarse odometer, or no movement), or the delta
     * is implausibly large (rollover / garbage) — letting the caller fall back to GPS.
     */
    private fun odometerDistanceKm(obd: List<ObdSample>): Double? {
        val readings = obd.mapNotNull { it.odometerKm }
        if (readings.size < 2) return null
        val delta = readings.last() - readings.first()
        return delta.takeIf { it > 0.0 && it <= MAX_PLAUSIBLE_ODOMETER_DELTA_KM }
    }

    /**
     * Sum of haversine hops along the GPS track, gated against stationary jitter. A phone sitting
     * still (a false auto-start next to an always-powered OBD port, or a phone-as-key walk-by) still
     * emits a fix every second, each a few metres off the last; summed raw that inflates into
     * hundreds of phantom metres and reads as a real trip. So we drop untrustworthy fixes, ignore a
     * hop shorter than the fixes' own accuracy (indistinguishable from noise), and skip hops where
     * both endpoints report a below-walking GPS speed (parked, not travelling).
     */
    private fun gpsDistanceKm(gps: List<GpsPoint>): Double? {
        val pts = gps.filter { (it.accuracyM ?: 0f) <= GPS_MAX_ACCURACY_M }
        if (pts.size < 2) return null
        var total = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val sa = a.speedKmh
            val sb = b.speedKmh
            if (sa != null && sb != null && sa < MOVING_THRESHOLD_KMH && sb < MOVING_THRESHOLD_KMH) continue
            val stepKm = DerivedMetrics.haversineKm(a.latitude, a.longitude, b.latitude, b.longitude)
            val floorM = maxOf(GPS_MIN_STEP_M, (a.accuracyM ?: 0f).toDouble(), (b.accuracyM ?: 0f).toDouble())
            if (stepKm * 1000.0 < floorM) continue
            total += stepKm
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

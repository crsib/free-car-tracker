package me.crsib.freecartracker.telemetry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure derivations from raw OBD/GPS values. Canonical units throughout. */
object DerivedMetrics {

    /** Instantaneous L/100km from fuel rate (L/h) and speed (km/h). Null when stationary. */
    fun l100FromFuelRate(fuelRateLph: Double?, speedKmh: Double?): Double? {
        if (fuelRateLph == null || speedKmh == null || speedKmh <= MIN_SPEED) return null
        return fuelRateLph / speedKmh * 100.0
    }

    /**
     * Instantaneous L/100km from MAF (g/s) and speed (km/h), assuming a stoichiometric petrol
     * mix. fuel(g/s) = MAF / AFR; convert to L/h via density, then to L/100km.
     */
    fun l100FromMaf(
        mafGramsPerSec: Double?,
        speedKmh: Double?,
        airFuelRatio: Double = PETROL_AFR,
        fuelDensityGramsPerLitre: Double = PETROL_DENSITY,
    ): Double? {
        if (mafGramsPerSec == null || speedKmh == null || speedKmh <= MIN_SPEED) return null
        val fuelGramsPerSec = mafGramsPerSec / airFuelRatio
        val fuelLitresPerHour = fuelGramsPerSec / fuelDensityGramsPerLitre * 3600.0
        return fuelLitresPerHour / speedKmh * 100.0
    }

    /** Prefer fuel-rate derivation, fall back to MAF. */
    fun l100(fuelRateLph: Double?, mafGramsPerSec: Double?, speedKmh: Double?): Double? =
        l100FromFuelRate(fuelRateLph, speedKmh) ?: l100FromMaf(mafGramsPerSec, speedKmh)

    /** CO2 (g/km) from consumption (L/100km). Petrol ≈ 2392 g CO2 per litre. */
    fun co2GkmFromL100(l100: Double?, gramsCo2PerLitre: Double = PETROL_CO2_PER_L): Double? =
        l100?.let { it / 100.0 * gramsCo2PerLitre }

    /** Great-circle distance between two coordinates, in kilometres. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // Instantaneous consumption is meaningless at a crawl: dividing fuel rate by a near-zero
    // (and noisy, for GPS) speed produces enormous L/100km spikes. Floor it at a real driving
    // speed so idling/creeping reads as "no value" rather than a bogus reading.
    private const val MIN_SPEED = 3.0
    private const val PETROL_AFR = 14.7
    private const val PETROL_DENSITY = 745.0 // g/L
    private const val PETROL_CO2_PER_L = 2392.0 // g CO2 per litre
    private const val EARTH_RADIUS_KM = 6371.0088
}

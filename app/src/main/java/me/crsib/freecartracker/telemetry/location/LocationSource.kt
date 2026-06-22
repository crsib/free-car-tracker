package me.crsib.freecartracker.telemetry.location

import kotlinx.coroutines.flow.Flow

/** A single GPS fix in canonical units (metres, km/h, degrees). */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val speedKmh: Double?,
    val bearingDeg: Float?,
    val accuracyM: Float?,
    val timestampMillis: Long,
    val provider: String,
)

/** Source of location updates. Has two implementations selected at runtime. */
interface LocationSource {
    /** Short name for diagnostics, e.g. "LocationManager" or "Fused". */
    val name: String

    fun isAvailable(): Boolean

    /** Cold flow of fixes; starts updates on collect, stops on cancel. */
    fun locations(intervalMs: Long): Flow<LocationFix>
}

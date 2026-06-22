package me.crsib.freecartracker.telemetry.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Guaranteed location source built on the platform [LocationManager]. Works with no Google
 * Play Services — the required path for car head units without GMS.
 */
@SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION verified by caller before collecting
class LocationManagerSource(context: Context) : LocationSource {
    override val name = "LocationManager"

    private val manager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun isAvailable(): Boolean =
        runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    override fun locations(intervalMs: Long): Flow<LocationFix> = callbackFlow {
        val listener = LocationListener { location -> trySend(location.toFix()) }
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMs,
            0f,
            listener,
            Looper.getMainLooper(),
        )
        awaitClose { manager.removeUpdates(listener) }
    }

    private fun Location.toFix(): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        altitudeM = if (hasAltitude()) altitude else null,
        speedKmh = if (hasSpeed()) speed.toDouble() * MS_TO_KMH else null,
        bearingDeg = if (hasBearing()) bearing else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        timestampMillis = time,
        provider = provider ?: LocationManager.GPS_PROVIDER,
    )

    private companion object {
        const val MS_TO_KMH = 3.6
    }
}

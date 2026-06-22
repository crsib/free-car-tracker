package me.crsib.freecartracker.telemetry.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Optional fused location source. Only constructed when Play Services is present (see
 * [LocationSourceFactory]); on a head unit without GMS, [LocationManagerSource] is used instead.
 */
@SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION verified by caller before collecting
class FusedLocationSource(context: Context) : LocationSource {
    override val name = "Fused"

    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    override fun isAvailable(): Boolean = true

    override fun locations(intervalMs: Long): Flow<LocationFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toFix()) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toFix(): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        altitudeM = if (hasAltitude()) altitude else null,
        speedKmh = if (hasSpeed()) speed.toDouble() * MS_TO_KMH else null,
        bearingDeg = if (hasBearing()) bearing else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        timestampMillis = time,
        provider = "fused",
    )

    private companion object {
        const val MS_TO_KMH = 3.6
    }
}

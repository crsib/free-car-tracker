package me.crsib.freecartracker.telemetry.location

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Picks the location source at runtime: Fused when Play Services is available, else the
 * platform LocationManager (the guaranteed path on no-GMS head units).
 */
class LocationSourceFactory(private val appContext: Context) {

    fun create(): LocationSource =
        if (isGooglePlayServicesAvailable()) {
            FusedLocationSource(appContext)
        } else {
            LocationManagerSource(appContext)
        }

    private fun isGooglePlayServicesAvailable(): Boolean = try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
    } catch (_: Throwable) {
        // If the GMS classes/services are entirely absent, fall back gracefully.
        false
    }
}

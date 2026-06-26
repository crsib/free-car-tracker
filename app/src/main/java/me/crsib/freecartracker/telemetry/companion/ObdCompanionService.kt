package me.crsib.freecartracker.telemetry.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.crsib.freecartracker.CarTrackerApp
import me.crsib.freecartracker.telemetry.service.TelemetryService

/**
 * Bound by the OS when the associated ELM327 comes into range (see [ObdCompanionManager]). If
 * auto-start is enabled and a vehicle is selected, it kicks off the foreground [TelemetryService]
 * — even after a cold process kill, so getting in the car is enough to start a trip.
 *
 * The presence notification grants a short window to start a foreground service from the
 * background; the companion permission REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND
 * makes that explicit on API 34+.
 */
class ObdCompanionService : CompanionDeviceService() {

    private val container get() = (application as CarTrackerApp).container

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "onDeviceAppeared: ${associationInfo.deviceMacAddress} — OS woke the service")
        container.tripRecorder.onAdapterPresenceGained()
        maybeStartRecording()
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "onDeviceDisappeared: ${associationInfo.deviceMacAddress}")
        // Don't end the trip outright — a brief dropout shouldn't. Just hint the recorder, which
        // auto-ends on a shortened grace once the OBD link is actually down (and a quick reconnect
        // cancels it).
        container.tripRecorder.onAdapterPresenceLost()
    }

    private fun maybeStartRecording() {
        if (!container.telemetrySettings.autoStartEnabled) {
            Log.i(TAG, "maybeStartRecording: skipped — auto-start disabled in settings")
            return
        }
        if (container.tripRecorder.isRecording) {
            Log.i(TAG, "maybeStartRecording: skipped — already recording")
            return
        }
        val vehicleId = resolveVehicleId()
        if (vehicleId == null) {
            Log.i(TAG, "maybeStartRecording: skipped — no vehicle resolved (none bound/selected)")
            return
        }
        Log.i(TAG, "maybeStartRecording: starting TelemetryService for vehicleId=$vehicleId")
        TelemetryService.start(this, vehicleId)
    }

    /**
     * Pick the car to record for. Prefer the vehicle bound to this adapter (if it still exists);
     * otherwise fall back to the currently-selected vehicle. A quick DB/DataStore read inside the
     * presence window; nothing to record without a vehicle.
     */
    private fun resolveVehicleId(): Long? = runCatching {
        runBlocking {
            val bound = container.telemetrySettings.adapterVehicleId
            if (bound != null && container.vehicleRepository.getById(bound) != null) {
                bound
            } else {
                container.vehicleSelection.observeSelected().first()?.id
            }
        }
    }.getOrNull()

    private companion object {
        const val TAG = "ObdAutoStart"
    }
}

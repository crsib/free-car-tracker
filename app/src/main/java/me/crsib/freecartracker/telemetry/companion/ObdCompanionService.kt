package me.crsib.freecartracker.telemetry.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.crsib.freecartracker.CarTrackerApp
import me.crsib.freecartracker.telemetry.DiagLog
import me.crsib.freecartracker.telemetry.service.TelemetryService

/**
 * Bound by the OS when the associated ELM327 comes into range (see [ObdCompanionManager]). If
 * auto-start is enabled and a vehicle is selected, it kicks off the foreground [TelemetryService]
 * — even after a cold process kill, so getting in the car is enough to start a trip.
 *
 * The presence notification grants a short window to start a foreground service from the
 * background; the companion permission REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND
 * makes that explicit on API 34+.
 *
 * Two callback paths, matching the two observation APIs in [ObdCompanionManager]:
 * - API 36+ observes via ObservingDevicePresenceRequest, which delivers [onDevicePresenceEvent]
 *   (the deprecated [onDeviceAppeared]/[onDeviceDisappeared] are NOT called on that path).
 * - Pre-36 observes via the legacy String API, which delivers [onDeviceAppeared]/[onDeviceDisappeared].
 */
class ObdCompanionService : CompanionDeviceService() {

    private val container get() = (application as CarTrackerApp).container

    /** API 36+ presence path (request-based observation). */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        DiagLog.i(TAG, "onDevicePresenceEvent: event=${event.event} assoc=${event.associationId}")
        if (!isConfiguredAdapter(event.associationId)) {
            DiagLog.i(TAG, "onDevicePresenceEvent: ignored — assoc ${event.associationId} isn't the configured OBD adapter")
            return
        }
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED -> onPresenceGained()

            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED -> onPresenceLost()
        }
    }

    /** Legacy presence path (pre-36 String-based observation). */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        DiagLog.i(TAG, "onDeviceAppeared (legacy): ${associationInfo.deviceMacAddress} — OS woke the service")
        if (!isConfiguredAdapter(associationInfo)) {
            DiagLog.i(TAG, "onDeviceAppeared: ignored — ${associationInfo.deviceMacAddress} isn't the configured OBD adapter")
            return
        }
        onPresenceGained()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        DiagLog.i(TAG, "onDeviceDisappeared (legacy): ${associationInfo.deviceMacAddress}")
        if (!isConfiguredAdapter(associationInfo)) return
        onPresenceLost()
    }

    /** True if the firing association is the OBD adapter saved in settings — not some other device. */
    private fun isConfiguredAdapter(associationId: Int): Boolean {
        val address = container.telemetrySettings.loadConnection().deviceAddress ?: return false
        return container.obdCompanionManager.associationIdFor(address) == associationId
    }

    private fun isConfiguredAdapter(info: AssociationInfo): Boolean {
        val address = container.telemetrySettings.loadConnection().deviceAddress ?: return false
        return info.deviceMacAddress?.toString().equals(address, ignoreCase = true)
    }

    private fun onPresenceGained() {
        container.tripRecorder.onAdapterPresenceGained()
        maybeStartRecording()
    }

    private fun onPresenceLost() {
        // Don't end the trip outright — a brief dropout shouldn't. Just hint the recorder, which
        // auto-ends on a shortened grace once the OBD link is actually down (and a quick reconnect
        // cancels it).
        container.tripRecorder.onAdapterPresenceLost()
    }

    private fun maybeStartRecording() {
        if (!container.telemetrySettings.autoStartEnabled) {
            DiagLog.i(TAG, "maybeStartRecording: skipped — auto-start disabled in settings")
            return
        }
        if (container.tripRecorder.isRecording) {
            DiagLog.i(TAG, "maybeStartRecording: skipped — already recording")
            return
        }
        val vehicleId = resolveVehicleId()
        if (vehicleId == null) {
            DiagLog.i(TAG, "maybeStartRecording: skipped — no vehicle resolved (none bound/selected)")
            return
        }
        DiagLog.i(TAG, "maybeStartRecording: starting TelemetryService for vehicleId=$vehicleId")
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

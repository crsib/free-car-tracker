package me.crsib.freecartracker.telemetry.companion

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender

/**
 * Wraps [CompanionDeviceManager] so the app can register the user's ELM327 as a companion device
 * and have the OS bind [ObdCompanionService] when it comes into range — even after a cold process
 * kill, which is the whole point of auto-start.
 *
 * BT-Classic only: WiFi adapters are always reachable on a network and BLE clones have no reliable
 * presence signal, so neither has a meaningful "the adapter just connected" moment to react to.
 */
class ObdCompanionManager(private val context: Context) {

    private val cdm: CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    /** Outcome of an [associate] attempt, surfaced to the caller. */
    sealed interface AssociateResult {
        /** Launch [intentSender] from an Activity to show the system confirmation dialog. */
        data class Pending(val intentSender: IntentSender) : AssociateResult

        /** Device was associated without needing a dialog. */
        data class Created(val info: AssociationInfo) : AssociateResult

        data class Failed(val reason: CharSequence?) : AssociateResult
    }

    fun isAvailable(): Boolean = cdm != null

    /** True if [address] is already associated with this app. */
    fun isAssociated(address: String): Boolean = associationFor(address) != null

    private fun associationFor(address: String): AssociationInfo? {
        val manager = cdm ?: return null
        return runCatching {
            manager.myAssociations.firstOrNull {
                it.deviceMacAddress?.toString().equals(address, ignoreCase = true)
            }
        }.getOrNull()
    }

    /**
     * Ask the system to associate the adapter at [address]. Results are delivered to [onResult]:
     * [AssociateResult.Pending] carries an [IntentSender] the caller must launch from an Activity;
     * once the user confirms, [AssociateResult.Created] follows. The [setSingleDevice] + exact
     * address filter means the dialog shows just this one adapter.
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested before reaching this screen
    fun associate(address: String, onResult: (AssociateResult) -> Unit) {
        val manager = cdm ?: return onResult(AssociateResult.Failed("Companion devices unsupported"))
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        manager.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) =
                onResult(AssociateResult.Pending(intentSender))

            override fun onAssociationCreated(associationInfo: AssociationInfo) =
                onResult(AssociateResult.Created(associationInfo))

            override fun onFailure(error: CharSequence?) = onResult(AssociateResult.Failed(error))
        })
    }

    /** Start OS-level presence monitoring for [address]; the adapter must already be associated. */
    fun startObserving(address: String) {
        val manager = cdm ?: return
        runCatching { manager.startObservingDevicePresence(address) }
    }

    fun stopObserving(address: String) {
        val manager = cdm ?: return
        runCatching { manager.stopObservingDevicePresence(address) }
    }

    /** Stop observing and drop the association for [address] entirely. */
    fun disassociate(address: String) {
        val manager = cdm ?: return
        runCatching { manager.stopObservingDevicePresence(address) }
        associationFor(address)?.let { info -> runCatching { manager.disassociate(info.id) } }
    }
}

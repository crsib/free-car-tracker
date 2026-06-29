package me.crsib.freecartracker.telemetry.companion

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import me.crsib.freecartracker.telemetry.DiagLog

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
        // associate() can throw synchronously (e.g. IllegalStateException if the OS rejects the
        // request) rather than routing through onFailure, so guard it to surface a Failed result.
        runCatching {
            manager.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) =
                    onResult(AssociateResult.Pending(intentSender))

                override fun onAssociationCreated(associationInfo: AssociationInfo) =
                    onResult(AssociateResult.Created(associationInfo))

                override fun onFailure(error: CharSequence?) = onResult(AssociateResult.Failed(error))
            })
        }.onFailure { onResult(AssociateResult.Failed(it.message)) }
    }

    /**
     * Start OS-level presence monitoring for [address]; the adapter must already be associated.
     *
     * On API 36+ the legacy [CompanionDeviceManager.startObservingDevicePresence] (String) is
     * deprecated and stops reliably re-binding [ObdCompanionService] across process death — the OS
     * only delivers callbacks to a live process, so a cold-killed app never auto-starts. The
     * request-based variant (observing by association id) is the one that re-binds the service from
     * cold, so we use it there and keep the legacy call only as the pre-36 fallback.
     */
    fun startObserving(address: String) {
        val manager = cdm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val id = associationFor(address)?.id
            if (id == null) {
                DiagLog.w(TAG, "startObserving: no association for $address — associate first; not observing")
                return
            }
            val request = ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()
            runCatching { manager.startObservingDevicePresence(request) }
                .onSuccess { DiagLog.i(TAG, "startObserving: observing associationId=$id ($address) via request API") }
                .onFailure { DiagLog.w(TAG, "startObserving: request observe failed for $address: ${it.message}") }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.startObservingDevicePresence(address) }
                .onSuccess { DiagLog.i(TAG, "startObserving: observing $address via legacy API") }
                .onFailure { DiagLog.w(TAG, "startObserving: legacy observe failed for $address: ${it.message}") }
        }
    }

    fun stopObserving(address: String) {
        val manager = cdm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val id = associationFor(address)?.id ?: return
            val request = ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()
            runCatching { manager.stopObservingDevicePresence(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.stopObservingDevicePresence(address) }
        }
    }

    /** Stop observing and drop the association for [address] entirely. */
    fun disassociate(address: String) {
        val manager = cdm ?: return
        stopObserving(address)
        associationFor(address)?.let { info -> runCatching { manager.disassociate(info.id) } }
    }

    private companion object {
        const val TAG = "ObdAutoStart"
    }
}

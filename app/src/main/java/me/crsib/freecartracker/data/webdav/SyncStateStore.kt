package me.crsib.freecartracker.data.webdav

import android.content.Context

/**
 * Per-car sync bookkeeping (not secret, so plain prefs). Tracks the `exportedAt` clock of the
 * ledger we last reconciled for each vehicle, so the manager can tell whether the remote
 * `ledger.json` is newer than what this device has seen (pull) or not (push, last-write-wins).
 */
class SyncStateStore(context: Context, prefsName: String = "webdav_sync_state") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun ledgerClock(vehicleSyncId: String): Long = prefs.getLong(key(vehicleSyncId), 0L)

    fun setLedgerClock(vehicleSyncId: String, exportedAt: Long) {
        prefs.edit().putLong(key(vehicleSyncId), exportedAt).apply()
    }

    /** [WebDavConfig.targetId] of the folder the trip upload marks refer to, or null if never recorded. */
    fun syncedTarget(): String? = prefs.getString(KEY_TARGET, null)

    fun setSyncedTarget(targetId: String) {
        prefs.edit().putString(KEY_TARGET, targetId).apply()
    }

    private fun key(vehicleSyncId: String) = "ledger_clock_$vehicleSyncId"

    private companion object {
        const val KEY_TARGET = "synced_target"
    }
}

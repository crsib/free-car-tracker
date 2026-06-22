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

    private fun key(vehicleSyncId: String) = "ledger_clock_$vehicleSyncId"
}

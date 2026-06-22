package me.crsib.freecartracker.data.webdav

import android.content.Context

/**
 * Requests a debounced one-shot WebDAV sync after a real change (app open, ledger edit, trip
 * finish). No-ops unless WebDAV is configured and auto-backup is on, so unconfigured users
 * enqueue nothing. The actual coalescing/network-gating happens in [WebDavBackupWorker.enqueueNow].
 */
class SyncTrigger(
    private val context: Context,
    private val isEnabled: () -> Boolean,
) {
    fun requestSync() {
        if (!isEnabled()) return
        WebDavBackupWorker.enqueueNow(context)
    }
}

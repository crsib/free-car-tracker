package me.crsib.freecartracker.ui.webdav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.crsib.freecartracker.data.webdav.SyncReport
import me.crsib.freecartracker.data.webdav.SyncStatus

/**
 * One-line readout of what sync is doing right now, or how the last run went. Driven by the
 * app-scoped `WebDavSyncManager.status`, so it reports background worker runs too — not just the
 * ones started from the button next to it.
 */
@Composable
fun SyncStatusLine(status: SyncStatus, modifier: Modifier = Modifier) {
    when (status) {
        // Nothing has run this process — the screen's "Last backup" line already covers history.
        is SyncStatus.Idle -> Unit

        is SyncStatus.Running -> Row(
            modifier = modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("${status.label}…", style = MaterialTheme.typography.bodySmall)
        }

        is SyncStatus.Done -> Text(
            text = status.report.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = if (status.report.isComplete) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                // Partial runs are normal while a backlog drains, so warn rather than alarm.
                MaterialTheme.colorScheme.tertiary
            },
            modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        )

        is SyncStatus.Failed -> Text(
            text = "Sync failed: ${status.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

/**
 * Plain-language outcome, as opposed to [SyncReport.summary] which is the dense arrow form kept for
 * the diagnostics log.
 */
private fun SyncReport.describe(): String {
    val moved = buildList {
        if (tripsUploaded > 0) add("$tripsUploaded ${trips(tripsUploaded)} uploaded")
        if (tripsImported > 0) add("$tripsImported ${trips(tripsImported)} downloaded")
        if (ledgersPushed > 0) add("$ledgersPushed ${ledgers(ledgersPushed)} uploaded")
        if (ledgersPulled > 0) add("$ledgersPulled ${ledgers(ledgersPulled)} downloaded")
        if (vehiclesPulled > 0) add("$vehiclesPulled ${cars(vehiclesPulled)} added")
        if (tripsDeleted + tripsRemoved > 0) add("${tripsDeleted + tripsRemoved} removed")
    }
    val what = if (moved.isEmpty()) "Up to date" else "Synced: ${moved.joinToString(", ")}"
    val leftover = buildList {
        if (deferred > 0) add("$deferred left for the next run")
        if (failed > 0) add("$failed failed")
    }
    return if (leftover.isEmpty()) what else "$what · ${leftover.joinToString(", ")}"
}

private fun trips(n: Int) = if (n == 1) "trip" else "trips"
private fun ledgers(n: Int) = if (n == 1) "ledger" else "ledgers"
private fun cars(n: Int) = if (n == 1) "car" else "cars"

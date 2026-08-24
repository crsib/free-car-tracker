package me.crsib.freecartracker.data.webdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.crsib.freecartracker.CarTrackerApp
import me.crsib.freecartracker.R
import me.crsib.freecartracker.telemetry.DiagLog
import java.util.concurrent.TimeUnit

/**
 * Runs an incremental WebDAV sync. This is the **only** execution path — events (app open, ledger
 * change, trip finish) and the "Sync now" button all come through [enqueueNow], with [schedule] as
 * a daily backstop.
 *
 * It promotes itself to a **foreground service** ([setForeground]) rather than running as an
 * ordinary worker, for two reasons that a trip backlog hits immediately: an ordinary worker is
 * killed at 10 minutes, and background network dies the moment the device sleeps. Running the sync
 * from a UI coroutine had the same sleep problem, which is why the button now enqueues instead of
 * calling the manager directly.
 *
 * Retrying is deliberately *bounded*. [enqueueNow] is unique work enqueued with
 * [ExistingWorkPolicy.KEEP], and WorkManager counts a request sitting in retry-backoff as still
 * pending — so a run that keeps returning [Result.retry] silently swallows every later trigger, and
 * the default exponential backoff pins that at one attempt per 5 hours within a day. Capping
 * attempts at [MAX_ATTEMPTS] over a short linear backoff guarantees the unique slot reaches a
 * terminal state quickly, so the next real event always gets a fresh run.
 */
class WebDavBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val manager get() = (applicationContext as CarTrackerApp).container.webDavSyncManager

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(SyncStatus.Idle)

    override suspend fun doWork(): Result = coroutineScope {
        // A manual tap syncs even with auto-backup off; only the automatic triggers are gated.
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && !manager.config().autoBackup) return@coroutineScope Result.success()

        // Without the foreground promotion we get the ordinary 10-minute cap and no protection from
        // Doze, so give the run a much smaller budget when it is refused.
        val promoted = runCatching { setForeground(getForegroundInfo()) }
            .onFailure { DiagLog.w(TAG, "setForeground denied: ${it::class.simpleName}: ${it.message}") }
            .isSuccess
        val budget = if (promoted) FOREGROUND_BUDGET_MILLIS else PLAIN_WORKER_BUDGET_MILLIS

        // Mirror live progress into the notification, so a sync with the screen off is visible.
        val mirror = launch {
            manager.status.collect { status ->
                if (promoted) runCatching { setForeground(foregroundInfo(status)) }
            }
        }

        val attempt = runAttemptCount + 1
        try {
            manager.syncNow(System.currentTimeMillis(), budgetMillis = budget).fold(
                onSuccess = { report ->
                    DiagLog.i(TAG, "sync ok (attempt $attempt): ${report.summary()}")
                    // A partial run persisted real progress; come back promptly for the rest rather
                    // than waiting a day, but still within the bounded attempt budget.
                    if (report.isComplete) Result.success() else retryOrGiveUp(attempt, "work remaining")
                },
                onFailure = { e ->
                    val reason = "${e::class.simpleName}: ${e.message}"
                    if (isPermanent(e)) {
                        DiagLog.w(TAG, "sync failed permanently, not retrying: $reason")
                        Result.failure()
                    } else {
                        DiagLog.w(TAG, "sync failed (attempt $attempt): $reason")
                        retryOrGiveUp(attempt, reason)
                    }
                },
            )
        } finally {
            mirror.cancelAndJoin()
        }
    }

    /**
     * Retry while attempts remain, otherwise end in a terminal state. Giving up is not "sync is
     * broken forever" — it just releases the unique-work slot, and the next trigger (or the daily
     * backstop) starts a clean run with a fresh attempt budget.
     */
    private fun retryOrGiveUp(attempt: Int, reason: String): Result =
        if (attempt < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            DiagLog.w(TAG, "giving up after $attempt attempts ($reason); next trigger starts fresh")
            Result.failure()
        }

    /** Bad credentials or missing config will fail identically on every retry — don't burn attempts. */
    private fun isPermanent(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("not configured") || message.contains("-> 401") || message.contains("-> 403")
    }

    private fun foregroundInfo(status: SyncStatus): ForegroundInfo {
        val context = applicationContext
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.webdav_sync_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.webdav_sync_channel_desc) },
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.webdav_sync_title))
            .setContentText((status as? SyncStatus.Running)?.label.orEmpty())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                val running = status as? SyncStatus.Running
                if (running != null && running.total > 0) {
                    setProgress(running.total, running.done, false)
                }
            }
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "WebDavSync"
        private const val WORK_NAME = "webdav-auto-backup"
        private const val ONESHOT_NAME = "webdav-sync-now"
        private const val CHANNEL_ID = "webdav_sync"
        private const val NOTIFICATION_ID = 43
        private const val KEY_MANUAL = "manual"

        /** Attempts per enqueued run, including the first. */
        private const val MAX_ATTEMPTS = 3

        /**
         * Short and linear, not the 30s-doubling-to-5-hours default: with [MAX_ATTEMPTS] the whole
         * run resolves inside ~5 minutes, which bounds how long KEEP can shadow new triggers.
         */
        private const val BACKOFF_MINUTES = 2L

        /** As a foreground service there is no 10-minute cap, so a real backlog can drain in one go. */
        private val FOREGROUND_BUDGET_MILLIS = TimeUnit.MINUTES.toMillis(30)

        /** Fallback when the foreground promotion is refused: stay well under the 10-minute kill. */
        private val PLAIN_WORKER_BUDGET_MILLIS = TimeUnit.MINUTES.toMillis(8)

        private val networkConstraint =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Daily backstop for missed events / failed retries. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Enqueue a one-shot sync. Automatic triggers get a short delay so a burst of events
         * (several entries added, many trips finishing) coalesces into a single run; a [manual] tap
         * starts immediately. KEEP means an already-pending run absorbs the request rather than
         * restarting the timer — and for a manual tap that is the right answer too, since a sync is
         * already under way.
         */
        fun enqueueNow(context: Context, manual: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<WebDavBackupWorker>()
                .setConstraints(networkConstraint)
                .setInputData(Data.Builder().putBoolean(KEY_MANUAL, manual).build())
                .setInitialDelay(if (manual) 0 else DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.KEEP, request)
        }

        private const val DEBOUNCE_SECONDS = 8L

        /**
         * One-time cleanup for the bounded-retry fix. A one-shot parked by the old unbounded
         * exponential backoff lives in WorkManager's own database, so it survives the app update
         * with its schedule intact — and KEEP goes on shadowing every new trigger until it finally
         * runs, up to five hours later. Drop it once so the first foreground after the update
         * syncs immediately. Call before anything can request a sync.
         */
        fun recoverStalledWork(context: Context) {
            val prefs = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_BACKOFF_RECOVERED, false)) return
            WorkManager.getInstance(context).cancelUniqueWork(ONESHOT_NAME)
            prefs.edit().putBoolean(KEY_BACKOFF_RECOVERED, true).apply()
            DiagLog.i(TAG, "cleared any one-shot parked by the pre-fix backoff")
        }

        private const val RECOVERY_PREFS = "webdav_sync_recovery"
        private const val KEY_BACKOFF_RECOVERED = "unbounded_backoff_cleared"

        /** Drop both the backstop and any parked one-shot, so turning auto-backup off is complete. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(ONESHOT_NAME)
            }
        }
    }
}

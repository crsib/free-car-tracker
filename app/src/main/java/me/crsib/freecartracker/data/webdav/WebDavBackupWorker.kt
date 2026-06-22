package me.crsib.freecartracker.data.webdav

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.crsib.freecartracker.CarTrackerApp
import java.util.concurrent.TimeUnit

/**
 * Runs an incremental WebDAV sync when auto-backup is enabled. Driven by events (app open,
 * ledger change, trip finish) via [enqueueNow], with [schedule] as a daily backstop.
 */
class WebDavBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CarTrackerApp).container
        if (!container.webDavSyncManager.config().autoBackup) return Result.success()
        return container.webDavSyncManager
            .syncNow(System.currentTimeMillis())
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val WORK_NAME = "webdav-auto-backup"
        private const val ONESHOT_NAME = "webdav-sync-now"

        private val networkConstraint =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Daily backstop for missed events / failed retries. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Enqueue a one-shot sync with a short delay so a burst of events (several entries added,
         * many trips finishing) coalesces into a single run. KEEP means an already-pending run
         * absorbs the new request rather than restarting the timer.
         */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WebDavBackupWorker>()
                .setConstraints(networkConstraint)
                .setInitialDelay(8, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

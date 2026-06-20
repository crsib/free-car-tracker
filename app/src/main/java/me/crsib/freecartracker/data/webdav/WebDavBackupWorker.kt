package me.crsib.freecartracker.data.webdav

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.crsib.freecartracker.CarTrackerApp
import java.util.concurrent.TimeUnit

/** Periodically uploads a backup to WebDAV when auto-backup is enabled. */
class WebDavBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CarTrackerApp).container
        if (!container.webDavBackupManager.config().autoBackup) return Result.success()
        return container.webDavBackupManager
            .backupNow(System.currentTimeMillis())
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val WORK_NAME = "webdav-auto-backup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

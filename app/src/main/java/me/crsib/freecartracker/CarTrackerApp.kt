package me.crsib.freecartracker

import android.app.Application
import me.crsib.freecartracker.data.webdav.WebDavBackupWorker
import me.crsib.freecartracker.di.AppContainer

class CarTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Re-arm the daily WebDAV backup if the user has enabled it.
        runCatching {
            if (container.webDavBackupManager.config().autoBackup) {
                WebDavBackupWorker.schedule(this)
            }
        }
    }
}

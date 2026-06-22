package me.crsib.freecartracker

import android.app.Application
import me.crsib.freecartracker.data.webdav.WebDavBackupWorker
import me.crsib.freecartracker.di.AppContainer
import org.osmdroid.config.Configuration
import java.io.File

class CarTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // osmdroid: identify ourselves to OSM tile servers and cache tiles in app-internal
        // storage so no external-storage permission is required (works on no-GMS head units).
        Configuration.getInstance().apply {
            load(this@CarTrackerApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        // Finalize any trip left mid-recording by a previous process death. This only launches
        // a coroutine (whose body guards itself), so a runCatching here would catch nothing.
        container.tripRecorder.recoverIncompleteTrips()
        // Re-arm the daily WebDAV backup if the user has enabled it.
        runCatching {
            if (container.webDavBackupManager.config().autoBackup) {
                WebDavBackupWorker.schedule(this)
            }
        }
    }
}

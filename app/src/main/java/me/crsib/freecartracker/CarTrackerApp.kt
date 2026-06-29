package me.crsib.freecartracker

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.crsib.freecartracker.data.webdav.WebDavBackupWorker
import me.crsib.freecartracker.di.AppContainer
import me.crsib.freecartracker.telemetry.DiagLog
import me.crsib.freecartracker.telemetry.obd.scan.DiagnosticFiles
import org.osmdroid.config.Configuration
import java.io.File

class CarTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Persist telemetry diagnostics to a file (logcat is wiped on reboot / unreachable on a
        // head unit), retrievable via the diagnostics force-sync.
        DiagLog.init(File(filesDir, DiagnosticFiles.TELEMETRY_LOGS))
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
        // Re-arm companion presence observation so a paired adapter can cold-start recording even
        // after a reboot. Cheap and idempotent; only does anything when the user opted in.
        runCatching {
            val settings = container.telemetrySettings
            if (settings.autoStartEnabled) {
                val address = settings.loadConnection().deviceAddress
                DiagLog.i("ObdAutoStart", "onCreate re-arm: autoStart on, observing $address")
                address?.let { container.obdCompanionManager.startObserving(it) }
            } else {
                DiagLog.i("ObdAutoStart", "onCreate re-arm: autoStart disabled — not observing")
            }
        }
        // Re-arm the daily WebDAV backup if the user has enabled it.
        runCatching {
            if (container.webDavSyncManager.config().autoBackup) {
                WebDavBackupWorker.schedule(this)
            }
        }
        // Sync when the app comes to the foreground, so a freshly-opened device pulls trips and
        // ledger edits made elsewhere. No-ops unless WebDAV is configured (gated in SyncTrigger).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.syncTrigger.requestSync()
            }
        })
        // Also sync the moment connectivity returns, so a trip finished offline (no signal in the
        // car) uploads without waiting for the next app launch. No-ops unless WebDAV is configured.
        container.connectivityWatcher.start()
    }
}

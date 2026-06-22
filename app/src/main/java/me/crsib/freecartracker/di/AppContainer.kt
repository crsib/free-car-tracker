package me.crsib.freecartracker.di

import android.content.Context
import me.crsib.freecartracker.data.AppDatabase
import me.crsib.freecartracker.data.VehicleSelection
import me.crsib.freecartracker.data.backup.BackupRepository
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.data.repository.EntryRepository
import me.crsib.freecartracker.data.repository.VehicleRepository
import me.crsib.freecartracker.data.webdav.SyncStateStore
import me.crsib.freecartracker.data.webdav.SyncTrigger
import me.crsib.freecartracker.data.webdav.WebDavClient
import me.crsib.freecartracker.data.webdav.WebDavSettingsStore
import me.crsib.freecartracker.data.webdav.WebDavSyncManager
import me.crsib.freecartracker.telemetry.data.TelemetryRepository
import me.crsib.freecartracker.telemetry.data.TelemetrySettingsStore
import me.crsib.freecartracker.telemetry.companion.ObdCompanionManager
import me.crsib.freecartracker.telemetry.location.LocationSourceFactory
import me.crsib.freecartracker.telemetry.obd.transport.TransportFactory
import me.crsib.freecartracker.telemetry.service.TripRecorder

/**
 * Manual dependency container (no Hilt — keeps build fast and APK small).
 * Holds app-scoped singletons.
 */
class AppContainer(val appContext: Context) {

    private val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val preferences: AppPreferences by lazy { AppPreferences(appContext) }

    val vehicleRepository: VehicleRepository by lazy {
        VehicleRepository(database.vehicleDao())
    }

    val entryRepository: EntryRepository by lazy {
        EntryRepository(
            fuelDao = database.fuelEntryDao(),
            chargeDao = database.chargeEntryDao(),
            expenseDao = database.expenseDao(),
            costDao = database.costDao(),
            onLedgerChanged = { syncTrigger.requestSync() },
        )
    }

    val telemetryRepository: TelemetryRepository by lazy {
        TelemetryRepository(
            tripDao = database.tripDao(),
            gpsPointDao = database.gpsPointDao(),
            obdSampleDao = database.obdSampleDao(),
            extendedDao = database.obdExtendedSampleDao(),
            dtcDao = database.dtcDao(),
        )
    }

    val telemetrySettings: TelemetrySettingsStore by lazy { TelemetrySettingsStore(appContext) }

    val transportFactory: TransportFactory by lazy { TransportFactory(appContext) }

    val obdCompanionManager: ObdCompanionManager by lazy { ObdCompanionManager(appContext) }

    val locationSourceFactory: LocationSourceFactory by lazy { LocationSourceFactory(appContext) }

    val tripRecorder: TripRecorder by lazy {
        TripRecorder(
            telemetryRepo = telemetryRepository,
            vehicleRepo = vehicleRepository,
            transportFactory = transportFactory,
            locationFactory = locationSourceFactory,
            onTripCompleted = { syncTrigger.requestSync() },
        )
    }

    val vehicleSelection: VehicleSelection by lazy {
        VehicleSelection(vehicleRepository, preferences)
    }

    val backupRepository: BackupRepository by lazy { BackupRepository(database) }

    private val webDavSettingsStore: WebDavSettingsStore by lazy { WebDavSettingsStore(appContext) }

    private val syncStateStore: SyncStateStore by lazy { SyncStateStore(appContext) }

    val webDavSyncManager: WebDavSyncManager by lazy {
        WebDavSyncManager(
            store = webDavSettingsStore,
            syncState = syncStateStore,
            client = WebDavClient(),
            db = database,
            telemetryRepo = telemetryRepository,
        )
    }

    /** Debounced sync trigger fired on ledger edits and trip completion (and app open). */
    val syncTrigger: SyncTrigger by lazy {
        SyncTrigger(appContext) {
            webDavSettingsStore.load().let { it.isConfigured && it.autoBackup }
        }
    }
}

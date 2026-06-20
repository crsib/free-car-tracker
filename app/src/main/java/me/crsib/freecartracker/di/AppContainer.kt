package me.crsib.freecartracker.di

import android.content.Context
import me.crsib.freecartracker.data.AppDatabase
import me.crsib.freecartracker.data.VehicleSelection
import me.crsib.freecartracker.data.backup.BackupRepository
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.data.repository.EntryRepository
import me.crsib.freecartracker.data.repository.VehicleRepository
import me.crsib.freecartracker.data.webdav.WebDavBackupManager
import me.crsib.freecartracker.data.webdav.WebDavClient
import me.crsib.freecartracker.data.webdav.WebDavSettingsStore

/**
 * Manual dependency container (no Hilt — keeps build fast and APK small).
 * Holds app-scoped singletons.
 */
class AppContainer(private val appContext: Context) {

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
        )
    }

    val vehicleSelection: VehicleSelection by lazy {
        VehicleSelection(vehicleRepository, preferences)
    }

    val backupRepository: BackupRepository by lazy { BackupRepository(database) }

    val webDavBackupManager: WebDavBackupManager by lazy {
        WebDavBackupManager(
            store = WebDavSettingsStore(appContext),
            client = WebDavClient(),
            backup = backupRepository,
        )
    }
}

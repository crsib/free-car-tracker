package me.crsib.freecartracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.VehicleSelection
import me.crsib.freecartracker.data.backup.BackupRepository
import me.crsib.freecartracker.data.backup.RestoreMode
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.data.prefs.ThemeMode
import me.crsib.freecartracker.data.webdav.WebDavSyncManager

class SettingsViewModel(
    private val prefs: AppPreferences,
    private val backup: BackupRepository,
    private val vehicleSelection: VehicleSelection,
    private val webDav: WebDavSyncManager,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ThemeMode.SYSTEM,
        )

    private val _syncBusy = MutableStateFlow(false)
    val syncBusy: StateFlow<Boolean> = _syncBusy.asStateFlow()

    /** True when a WebDAV server is configured and a sync can be triggered. */
    val isWebDavConfigured: Boolean get() = webDav.config().isConfigured

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }

    /**
     * Run a manual bidirectional sync with the same path the worker uses. Returns a
     * human-readable result message for the snackbar.
     */
    suspend fun syncNow(now: Long): String {
        _syncBusy.value = true
        return try {
            webDav.syncNow(now).fold(
                onSuccess = { report -> "Synced. ${report.summary()}" },
                onFailure = { e -> "Sync failed: ${e.message}" },
            )
        } finally {
            _syncBusy.value = false
        }
    }

    suspend fun buildJson(now: Long): String = backup.exportJson(now)

    suspend fun buildCsv(): String = backup.exportCsv()

    /** Returns a human-readable result message. */
    suspend fun restoreFromText(text: String, mode: RestoreMode): String =
        runCatching {
            val data = backup.parse(text)
            backup.restore(data, mode)
            val count = data.fuelEntries.size + data.chargeEntries.size + data.expenses.size
            "Restored ${data.vehicles.size} vehicles and $count entries."
        }.getOrElse { "Import failed: ${it.message ?: "invalid file"}" }

    /** Imports a Drivvo CSV export into the selected vehicle. Returns a human-readable result. */
    suspend fun importDrivvo(text: String): String {
        val vehicle = runCatching { vehicleSelection.observeSelected().first() }.getOrNull()
            ?: return "Add a vehicle first, then import."
        return runCatching {
            val r = backup.importDrivvoCsv(text, vehicle.id, vehicle.currency)
            "Imported ${r.fuel.size} fuel, ${r.charges.size} charge and ${r.expenses.size} " +
                "expense entries into ${vehicle.name}."
        }.getOrElse { "Import failed: ${it.message ?: "invalid file"}" }
    }
}

package me.crsib.freecartracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.VehicleSelection
import me.crsib.freecartracker.data.backup.BackupRepository
import me.crsib.freecartracker.data.backup.RestoreMode
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.data.prefs.ThemeMode

class SettingsViewModel(
    private val prefs: AppPreferences,
    private val backup: BackupRepository,
    private val vehicleSelection: VehicleSelection,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ThemeMode.SYSTEM,
        )

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }

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

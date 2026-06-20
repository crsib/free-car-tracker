package me.crsib.freecartracker.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** How the app picks its color scheme. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Lightweight user/app preferences (selected vehicle, dashboard window). */
class AppPreferences(private val context: Context) {

    val selectedVehicleId: Flow<Long?> =
        context.dataStore.data.map { it[KEY_SELECTED_VEHICLE] }

    val dashboardWindowDays: Flow<Int> =
        context.dataStore.data.map { it[KEY_DASHBOARD_DAYS] ?: DEFAULT_WINDOW_DAYS }

    val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    suspend fun setSelectedVehicleId(id: Long) {
        context.dataStore.edit { it[KEY_SELECTED_VEHICLE] = id }
    }

    suspend fun setDashboardWindowDays(days: Int) {
        context.dataStore.edit { it[KEY_DASHBOARD_DAYS] = days }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    companion object {
        const val DEFAULT_WINDOW_DAYS = 30

        /** Sentinel window meaning "every entry, no date cut-off". */
        const val ALL_TIME_DAYS = 0

        /** Window lengths offered in the UI (days); [ALL_TIME_DAYS] is "all time". */
        val WINDOW_OPTIONS = listOf(7, 14, 30, 60, 90, ALL_TIME_DAYS)

        private val KEY_SELECTED_VEHICLE = longPreferencesKey("selected_vehicle_id")
        private val KEY_DASHBOARD_DAYS = intPreferencesKey("dashboard_window_days")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}

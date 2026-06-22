package me.crsib.freecartracker.telemetry.data

import android.content.Context
import kotlinx.serialization.json.Json
import me.crsib.freecartracker.telemetry.obd.transport.ObdConnectionConfig

/** Persists the chosen OBD adapter connection config as JSON in SharedPreferences. */
class TelemetrySettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadConnection(): ObdConnectionConfig {
        val raw = prefs.getString(KEY_CONNECTION, null) ?: return ObdConnectionConfig()
        return runCatching { json.decodeFromString(ObdConnectionConfig.serializer(), raw) }
            .getOrDefault(ObdConnectionConfig())
    }

    fun saveConnection(config: ObdConnectionConfig) {
        prefs.edit()
            .putString(KEY_CONNECTION, json.encodeToString(ObdConnectionConfig.serializer(), config))
            .apply()
    }

    private companion object {
        const val KEY_CONNECTION = "obd_connection"
    }
}

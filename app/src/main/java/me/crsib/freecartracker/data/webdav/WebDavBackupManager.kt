package me.crsib.freecartracker.data.webdav

import me.crsib.freecartracker.data.backup.BackupRepository
import me.crsib.freecartracker.data.backup.RestoreMode

/** Coordinates WebDAV upload/download with the local backup serializer. */
class WebDavBackupManager(
    private val store: WebDavSettingsStore,
    private val client: WebDavClient,
    private val backup: BackupRepository,
) {
    fun config(): WebDavConfig = store.load()

    fun saveConfig(config: WebDavConfig) = store.save(config)

    suspend fun testConnection(config: WebDavConfig): Result<Unit> = client.testConnection(config)

    suspend fun backupNow(now: Long): Result<Unit> {
        val config = store.load()
        if (!config.isConfigured) return Result.failure(IllegalStateException("WebDAV not configured"))
        val json = backup.exportJson(now)
        return client.upload(config, json).onSuccess { store.setLastBackup(now) }
    }

    suspend fun restoreNow(mode: RestoreMode): Result<String> {
        val config = store.load()
        if (!config.isConfigured) return Result.failure(IllegalStateException("WebDAV not configured"))
        return client.download(config).mapCatching { text ->
            val data = backup.parse(text)
            backup.restore(data, mode)
            val count = data.fuelEntries.size + data.chargeEntries.size + data.expenses.size
            "Restored ${data.vehicles.size} vehicles and $count entries."
        }
    }
}

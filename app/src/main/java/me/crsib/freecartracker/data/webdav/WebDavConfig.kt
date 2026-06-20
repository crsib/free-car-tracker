package me.crsib.freecartracker.data.webdav

/** WebDAV connection settings. [url] is the target directory URL (with trailing slash). */
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val autoBackup: Boolean = false,
    val lastBackupMillis: Long = 0,
) {
    val isConfigured: Boolean get() = url.isNotBlank() && username.isNotBlank()

    companion object {
        const val BACKUP_FILENAME = "car-tracker-backup.json"
    }
}

package me.crsib.freecartracker.data.webdav

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Persists WebDAV credentials in an encrypted SharedPreferences file. */
class WebDavSettingsStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "webdav_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): WebDavConfig = WebDavConfig(
        url = prefs.getString(KEY_URL, "").orEmpty(),
        username = prefs.getString(KEY_USER, "").orEmpty(),
        password = prefs.getString(KEY_PASS, "").orEmpty(),
        autoBackup = prefs.getBoolean(KEY_AUTO, false),
        lastBackupMillis = prefs.getLong(KEY_LAST, 0),
    )

    fun save(config: WebDavConfig) {
        prefs.edit()
            .putString(KEY_URL, config.url.trim())
            .putString(KEY_USER, config.username.trim())
            .putString(KEY_PASS, config.password)
            .putBoolean(KEY_AUTO, config.autoBackup)
            .putLong(KEY_LAST, config.lastBackupMillis)
            .apply()
    }

    fun setLastBackup(millis: Long) {
        prefs.edit().putLong(KEY_LAST, millis).apply()
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_AUTO = "auto_backup"
        const val KEY_LAST = "last_backup"
    }
}

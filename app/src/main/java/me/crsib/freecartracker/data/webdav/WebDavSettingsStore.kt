package me.crsib.freecartracker.data.webdav

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Persists the sync target and its credentials (WebDAV or S3) in an encrypted SharedPreferences file. */
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
        // Absent on installs that predate S3 support, which were all WebDAV.
        backend = prefs.getString(KEY_BACKEND, null)
            ?.let { runCatching { SyncBackend.valueOf(it) }.getOrNull() }
            ?: SyncBackend.WEBDAV,
        s3 = S3Config(
            endpoint = prefs.getString(KEY_S3_ENDPOINT, "").orEmpty(),
            region = prefs.getString(KEY_S3_REGION, S3Config.DEFAULT_REGION).orEmpty(),
            bucket = prefs.getString(KEY_S3_BUCKET, "").orEmpty(),
            prefix = prefs.getString(KEY_S3_PREFIX, "").orEmpty(),
            accessKeyId = prefs.getString(KEY_S3_ACCESS_KEY, "").orEmpty(),
            secretAccessKey = prefs.getString(KEY_S3_SECRET_KEY, "").orEmpty(),
            pathStyle = prefs.getBoolean(KEY_S3_PATH_STYLE, true),
        ),
    )

    fun save(config: WebDavConfig) {
        prefs.edit()
            .putString(KEY_URL, config.url.trim())
            .putString(KEY_USER, config.username.trim())
            .putString(KEY_PASS, config.password)
            .putBoolean(KEY_AUTO, config.autoBackup)
            .putLong(KEY_LAST, config.lastBackupMillis)
            .putString(KEY_BACKEND, config.backend.name)
            .putString(KEY_S3_ENDPOINT, config.s3.endpoint.trim())
            .putString(KEY_S3_REGION, config.s3.region.trim())
            .putString(KEY_S3_BUCKET, config.s3.bucket.trim())
            .putString(KEY_S3_PREFIX, config.s3.prefix.trim().trim('/'))
            .putString(KEY_S3_ACCESS_KEY, config.s3.accessKeyId.trim())
            .putString(KEY_S3_SECRET_KEY, config.s3.secretAccessKey.trim())
            .putBoolean(KEY_S3_PATH_STYLE, config.s3.pathStyle)
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
        const val KEY_BACKEND = "backend"
        const val KEY_S3_ENDPOINT = "s3_endpoint"
        const val KEY_S3_REGION = "s3_region"
        const val KEY_S3_BUCKET = "s3_bucket"
        const val KEY_S3_PREFIX = "s3_prefix"
        const val KEY_S3_ACCESS_KEY = "s3_access_key"
        const val KEY_S3_SECRET_KEY = "s3_secret_key"
        const val KEY_S3_PATH_STYLE = "s3_path_style"
    }
}

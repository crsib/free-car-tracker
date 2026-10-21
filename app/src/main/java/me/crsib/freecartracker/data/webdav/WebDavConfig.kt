package me.crsib.freecartracker.data.webdav

/** Which kind of server the sync folder lives on. Both hold the same [WebDavPaths] layout. */
enum class SyncBackend { WEBDAV, S3 }

/**
 * Remote sync settings. Named for WebDAV, its first backend; [backend] selects whether [url] /
 * [username] / [password] (WebDAV) or [s3] is the live target. [autoBackup] and
 * [lastBackupMillis] apply to whichever is selected.
 */
data class WebDavConfig(
    /** WebDAV: the target directory URL (with trailing slash). */
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val autoBackup: Boolean = false,
    val lastBackupMillis: Long = 0,
    val backend: SyncBackend = SyncBackend.WEBDAV,
    val s3: S3Config = S3Config(),
) {
    val isConfigured: Boolean
        get() = when (backend) {
            SyncBackend.WEBDAV -> url.isNotBlank() && username.isNotBlank()
            SyncBackend.S3 -> s3.isConfigured
        }

    /**
     * Identifies the remote folder itself — not the credentials used to reach it, so rotating a
     * password or key is not mistaken for a move. Two configs with the same id hold the same files.
     */
    val targetId: String
        get() = when (backend) {
            SyncBackend.WEBDAV -> "webdav:" + url.trim().trimEnd('/').lowercase()
            SyncBackend.S3 -> "s3:${s3.effectiveEndpoint.lowercase()}|${s3.bucket.trim()}|${s3.prefix.trim().trim('/')}"
        }

    companion object {
        const val BACKUP_FILENAME = "car-tracker-backup.json"
    }
}

/**
 * An S3 (or S3-compatible: MinIO, Cloudflare R2, Backblaze B2, Yandex Object Storage, …) bucket.
 * The sync layout lives under [prefix] inside [bucket], so one bucket can hold other data too.
 */
data class S3Config(
    /** Service endpoint, e.g. `https://s3.eu-central-1.amazonaws.com`. Blank means AWS for [region]. */
    val endpoint: String = "",
    /** Signing region. R2 wants `auto`; MinIO accepts anything, conventionally `us-east-1`. */
    val region: String = DEFAULT_REGION,
    val bucket: String = "",
    /** Folder inside the bucket, without leading or trailing slash. Blank = bucket root. */
    val prefix: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    /**
     * `https://host/bucket/key` rather than `https://bucket.host/key`. Every S3-compatible store
     * accepts path style, and it needs no wildcard DNS or certificate, so it is the default.
     */
    val pathStyle: Boolean = true,
) {
    val isConfigured: Boolean
        get() = bucket.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

    val effectiveRegion: String get() = region.trim().ifEmpty { DEFAULT_REGION }

    val effectiveEndpoint: String
        get() = endpoint.trim().trimEnd('/').ifEmpty { "https://s3.$effectiveRegion.amazonaws.com" }

    companion object {
        const val DEFAULT_REGION = "us-east-1"
    }
}

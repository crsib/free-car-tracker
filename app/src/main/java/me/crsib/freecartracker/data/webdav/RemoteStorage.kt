package me.crsib.freecartracker.data.webdav

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The sync folder, addressed by paths relative to its root (see [WebDavPaths]), independent of
 * whether it lives on a WebDAV server or in an S3 bucket. A path ending in `/` names a folder.
 * [WebDavSyncManager] only ever talks to this, so both backends hold byte-identical layouts and
 * the viewer can read either.
 */
interface RemoteStorage {
    /** Verify the target is reachable and the credentials are accepted. */
    suspend fun testConnection(): Result<Unit>

    /**
     * Make sure folder [relPath] and its parents exist. [created] memoizes prefixes already done
     * in this run — see [WebDavClient.ensureCollection]. A no-op where folders are implicit (S3).
     */
    suspend fun ensureCollection(relPath: String, created: MutableSet<String>? = null): Result<Unit>

    /** Immediate children of folder [relPath]. A missing folder lists as empty. */
    suspend fun list(relPath: String): Result<List<DavEntry>>

    /** True if a file exists at [relPath]. */
    suspend fun exists(relPath: String): Result<Boolean>

    /** Upload a small JSON document. */
    suspend fun putBytes(relPath: String, bytes: ByteArray): Result<Unit>

    /** Upload bytes already compressed by [gzip], with the long timeouts large files need. */
    suspend fun putGzipped(relPath: String, gz: ByteArray): Result<Unit>

    /** Download raw bytes, or null if missing. */
    suspend fun getBytes(relPath: String): Result<ByteArray?>

    /** Download and gunzip, or null if missing. On the long-timeout path — these are the big files. */
    suspend fun getGunzip(relPath: String): Result<ByteArray?>

    /** Delete a file, or a whole folder (trailing slash) recursively. Already-gone counts as success. */
    suspend fun delete(relPath: String): Result<Unit>

    /** gzip [plain] and upload it. */
    suspend fun putGzip(relPath: String, plain: ByteArray): Result<Unit> = putGzipped(relPath, gzip(plain))

    companion object {
        /** The storage [config] selects, bound to its credentials. */
        fun forConfig(config: WebDavConfig, webDav: WebDavClient, s3: S3Client): RemoteStorage =
            when (config.backend) {
                SyncBackend.WEBDAV -> WebDavStorage(webDav, config)
                SyncBackend.S3 -> S3Storage(s3, config.s3)
            }
    }
}

/** [RemoteStorage] over a WebDAV collection. A thin binding of [WebDavClient] to one config. */
class WebDavStorage(private val client: WebDavClient, private val config: WebDavConfig) : RemoteStorage {
    override suspend fun testConnection() = client.testConnection(config)
    override suspend fun ensureCollection(relPath: String, created: MutableSet<String>?) =
        client.ensureCollection(config, relPath, created)
    override suspend fun list(relPath: String) = client.list(config, relPath)
    override suspend fun exists(relPath: String) = client.exists(config, relPath)
    override suspend fun putBytes(relPath: String, bytes: ByteArray) = client.putBytes(config, relPath, bytes)
    override suspend fun putGzipped(relPath: String, gz: ByteArray) = client.putGzipped(config, relPath, gz)
    override suspend fun getBytes(relPath: String) = client.getBytes(config, relPath)
    override suspend fun getGunzip(relPath: String) = client.getGunzip(config, relPath)
    override suspend fun delete(relPath: String) = client.delete(config, relPath)
}

fun gzip(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
}

fun gunzip(data: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

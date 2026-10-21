package me.crsib.freecartracker.data.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** One child of a remote folder: a WebDAV Depth:1 PROPFIND entry, or an S3 key / common prefix. */
data class DavEntry(
    /** Last decoded path segment, i.e. the file or directory name. */
    val name: String,
    val isCollection: Boolean,
    /** Server `getlastmodified` in epoch millis, or null if absent/unparseable. */
    val lastModifiedMillis: Long? = null,
)

/**
 * Path-addressed WebDAV client over OkHttp. Every method takes a path relative to the
 * configured root ([WebDavConfig.url]). Supports the directory operations the multi-file
 * sync layout needs: MKCOL (with parent creation), PROPFIND Depth:0/1, and gzip PUT/GET.
 */
class WebDavClient(
    private val client: OkHttpClient = defaultHttpClient(),
) {
    /**
     * Trip payloads are a different class of transfer from the small JSON documents: a 10 Hz
     * `obd.csv.gz` for a long drive is tens of megabytes, and on a weak mobile uplink a single
     * socket write can stall well past a minute without the transfer being dead. Observed on the
     * Honor: whole trips still aborting with `SocketTimeoutException` at a 60s write timeout.
     * Shares the parent's connection pool and dispatcher, so this costs nothing but the config.
     */
    private val largeTransferClient: OkHttpClient = largeTransfer(client)

    private val jsonMedia = "application/json".toMediaType()
    private val gzipMedia = "application/gzip".toMediaType()

    // --- URL building ---

    private fun absUrl(config: WebDavConfig, relPath: String): String {
        val builder = config.url.toHttpUrl().newBuilder()
        relPath.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        // Preserve a trailing slash for collection paths (some servers are picky).
        if (relPath.endsWith('/')) builder.addPathSegment("")
        return builder.build().toString()
    }

    private fun authed(config: WebDavConfig, url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", Credentials.basic(config.username, config.password))

    // --- Collections ---

    /** Create a single collection. 201 (created) and 405 (already exists) both count as success. */
    suspend fun mkcol(config: WebDavConfig, relPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(authed(config, absUrl(config, relPath)).method("MKCOL", null).build()).execute().use { r ->
                check(r.isSuccessful || r.code == 405 || r.code == 301) { "MKCOL $relPath -> ${r.code}" }
            }
        }
    }

    /**
     * Create [relPath] and every missing parent collection, segment by segment, as
     * Apache/Nextcloud require (they 409 on a PUT/MKCOL whose parent is absent).
     *
     * Pass [created] to memoize prefixes already handled: uploading 61 trips walks
     * `{car}/trips/YYYY/MM/` afresh every time, so four of the five MKCOLs per trip are pure
     * round-trip cost against a folder that demonstrably exists. Scope the set to one sync run —
     * caching it longer would miss a folder deleted on the server between runs.
     */
    suspend fun ensureCollection(
        config: WebDavConfig,
        relPath: String,
        created: MutableSet<String>? = null,
    ): Result<Unit> = runCatching {
        val segments = relPath.trim('/').split('/').filter { it.isNotEmpty() }
        val prefix = StringBuilder()
        for (seg in segments) {
            prefix.append(seg).append('/')
            val path = prefix.toString()
            if (created != null && !created.add(path)) continue
            mkcol(config, path).getOrThrow()
        }
    }

    /** PROPFIND Depth:1 listing of a collection's immediate children (self entry excluded). */
    suspend fun list(config: WebDavConfig, relPath: String): Result<List<DavEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = absUrl(config, if (relPath.endsWith('/') || relPath.isEmpty()) relPath else "$relPath/")
            val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
            val request = authed(config, url).method("PROPFIND", body).header("Depth", "1").build()
            client.newCall(request).execute().use { r ->
                if (r.code == 404) return@use emptyList()
                check(r.isSuccessful || r.code == 207) { "PROPFIND $relPath -> ${r.code}" }
                val xml = r.body?.bytes() ?: return@use emptyList()
                parseMultistatus(xml, selfPath = url.toHttpUrl().encodedPath)
            }
        }
    }

    /** True if a resource exists at [relPath] (PROPFIND Depth:0; 404 -> false). */
    suspend fun exists(config: WebDavConfig, relPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val body = PROPFIND_BODY.toRequestBody("application/xml".toMediaType())
            val request = authed(config, absUrl(config, relPath)).method("PROPFIND", body).header("Depth", "0").build()
            client.newCall(request).execute().use { r ->
                when {
                    r.code == 404 -> false
                    r.isSuccessful || r.code == 207 -> true
                    else -> error("PROPFIND $relPath -> ${r.code}")
                }
            }
        }
    }

    // --- Files ---

    suspend fun putBytes(
        config: WebDavConfig,
        relPath: String,
        bytes: ByteArray,
        media: okhttp3.MediaType = jsonMedia,
        http: OkHttpClient = client,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(authed(config, absUrl(config, relPath)).put(bytes.toRequestBody(media)).build())
                .execute().use { r -> check(r.isSuccessful) { "PUT $relPath -> ${r.code}" } }
        }
    }

    /** GET raw bytes, or null if the resource is missing (404). */
    suspend fun getBytes(
        config: WebDavConfig,
        relPath: String,
        http: OkHttpClient = client,
    ): Result<ByteArray?> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(authed(config, absUrl(config, relPath)).get().build()).execute().use { r ->
                if (r.code == 404) null
                else { check(r.isSuccessful) { "GET $relPath -> ${r.code}" }; r.body?.bytes() }
            }
        }
    }

    /**
     * DELETE a resource. For a collection path (trailing slash) WebDAV removes it recursively.
     * A 404 counts as success — the target is already gone, which is what we wanted.
     */
    suspend fun delete(config: WebDavConfig, relPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(authed(config, absUrl(config, relPath)).delete().build()).execute().use { r ->
                check(r.isSuccessful || r.code == 404) { "DELETE $relPath -> ${r.code}" }
            }
        }
    }

    /** gzip [plain] and PUT it (for the large `.gz` trip files), on the long-timeout client. */
    suspend fun putGzip(config: WebDavConfig, relPath: String, plain: ByteArray): Result<Unit> =
        putBytes(config, relPath, gzip(plain), gzipMedia, largeTransferClient)

    /**
     * gzip [plain] without uploading it, for a caller that needs the on-wire size up front (to show
     * progress) and would otherwise have to compress the same bytes twice. Pair with [putGzipped].
     */
    fun gzipped(plain: ByteArray): ByteArray = gzip(plain)

    /** PUT bytes already compressed by [gzipped], on the long-timeout client. */
    suspend fun putGzipped(config: WebDavConfig, relPath: String, gz: ByteArray): Result<Unit> =
        putBytes(config, relPath, gz, gzipMedia, largeTransferClient)

    /** GET and gunzip, or null if missing. On the long-timeout client — these are the big files. */
    suspend fun getGunzip(config: WebDavConfig, relPath: String): Result<ByteArray?> =
        getBytes(config, relPath, largeTransferClient).map { it?.let(::gunzip) }

    // --- Legacy single-file API (still used by the current backup manager; removed in the sync rewrite) ---

    private fun fileUrl(config: WebDavConfig): String {
        val base = if (config.url.endsWith("/")) config.url else config.url + "/"
        return base + WebDavConfig.BACKUP_FILENAME
    }

    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authed(config, if (config.url.endsWith("/")) config.url else config.url + "/")
                .method("PROPFIND", null).header("Depth", "0").build()
            client.newCall(request).execute().use { resp ->
                check(resp.isSuccessful || resp.code == 207) { "Server returned ${resp.code}" }
            }
        }
    }

    suspend fun upload(config: WebDavConfig, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authed(config, fileUrl(config))
                .put(content.toByteArray().toRequestBody(jsonMedia)).build()
            client.newCall(request).execute().use { resp -> check(resp.isSuccessful) { "Upload failed: ${resp.code}" } }
        }
    }

    suspend fun download(config: WebDavConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authed(config, fileUrl(config)).get().build()
            client.newCall(request).execute().use { resp: Response ->
                check(resp.isSuccessful) { "Download failed: ${resp.code}" }
                resp.body?.string() ?: error("Empty response")
            }
        }
    }

    // --- helpers ---

    /**
     * Parse a `207 Multi-Status` body into child [DavEntry]s. Namespace-aware DOM matching on the
     * `DAV:` namespace, so it is robust to the prefix the server uses (`D:`/`d:`/`lp1:`). The
     * response whose href equals [selfPath] (the collection itself) is skipped.
     */
    private fun parseMultistatus(xml: ByteArray, selfPath: String): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val responses = doc.getElementsByTagNameNS(DAV_NS, "response")
        val selfNorm = selfPath.trimEnd('/')
        val out = ArrayList<DavEntry>()
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as org.w3c.dom.Element
            val hrefEl = resp.getElementsByTagNameNS(DAV_NS, "href").item(0) ?: continue
            val rawHref = hrefEl.textContent.trim()
            val path = runCatching { rawHref.toHttpUrl().encodedPath }.getOrDefault(rawHref).trimEnd('/')
            if (path == selfNorm) continue // the collection itself
            val name = URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
            if (name.isEmpty()) continue
            val isCollection = resp.getElementsByTagNameNS(DAV_NS, "collection").length > 0
            val lastMod = resp.getElementsByTagNameNS(DAV_NS, "getlastmodified").item(0)
                ?.textContent?.let { parseHttpDate(it) }
            out += DavEntry(name = name, isCollection = isCollection, lastModifiedMillis = lastMod)
        }
        return out
    }

    private fun parseHttpDate(value: String): Long? = runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .parse(value.trim())?.time
    }.getOrNull()

    companion object {
        /** The HTTP client for sync traffic. Shared with [S3Client], so both backends time out alike. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        /** [base] with the long per-operation timeouts the multi-MB trip files need. */
        fun largeTransfer(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .readTimeout(LARGE_TRANSFER_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(LARGE_TRANSFER_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()

        private const val CONNECT_TIMEOUT_SECONDS = 15L

        /**
         * Applies to reads *and* writes. OkHttp defaults both to 10s; leaving the write side at the
         * default meant a multi-MB gzipped `obd.csv.gz` PUT over a weak head-unit uplink aborted with
         * a SocketTimeoutException long before it finished, which is what "sync fails with timeout"
         * actually was. These are per-socket-operation timeouts, not a cap on the whole transfer.
         */
        private const val IO_TIMEOUT_SECONDS = 60L

        /** Per-socket-operation ceiling for the multi-MB trip files. See [largeTransferClient]. */
        private const val LARGE_TRANSFER_TIMEOUT_MINUTES = 5L

        private const val DAV_NS = "DAV:"
        private val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<d:propfind xmlns:d="DAV:"><d:prop>""" +
                """<d:resourcetype/><d:getlastmodified/>""" +
                """</d:prop></d:propfind>"""
    }
}

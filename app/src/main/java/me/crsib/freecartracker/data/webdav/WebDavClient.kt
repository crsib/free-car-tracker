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
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One child resource of a WebDAV collection, from a Depth:1 PROPFIND listing. */
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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
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
     */
    suspend fun ensureCollection(config: WebDavConfig, relPath: String): Result<Unit> = runCatching {
        val segments = relPath.trim('/').split('/').filter { it.isNotEmpty() }
        val prefix = StringBuilder()
        for (seg in segments) {
            prefix.append(seg).append('/')
            mkcol(config, prefix.toString()).getOrThrow()
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(authed(config, absUrl(config, relPath)).put(bytes.toRequestBody(media)).build())
                .execute().use { r -> check(r.isSuccessful) { "PUT $relPath -> ${r.code}" } }
        }
    }

    /** GET raw bytes, or null if the resource is missing (404). */
    suspend fun getBytes(config: WebDavConfig, relPath: String): Result<ByteArray?> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(authed(config, absUrl(config, relPath)).get().build()).execute().use { r ->
                if (r.code == 404) null
                else { check(r.isSuccessful) { "GET $relPath -> ${r.code}" }; r.body?.bytes() }
            }
        }
    }

    /** gzip [plain] and PUT it (for the large `.gz` trip files). */
    suspend fun putGzip(config: WebDavConfig, relPath: String, plain: ByteArray): Result<Unit> =
        putBytes(config, relPath, gzip(plain), gzipMedia)

    /** GET and gunzip, or null if missing. */
    suspend fun getGunzip(config: WebDavConfig, relPath: String): Result<ByteArray?> =
        getBytes(config, relPath).map { it?.let(::gunzip) }

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

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

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
        private const val DAV_NS = "DAV:"
        private val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<d:propfind xmlns:d="DAV:"><d:prop>""" +
                """<d:resourcetype/><d:getlastmodified/>""" +
                """</d:prop></d:propfind>"""
    }
}

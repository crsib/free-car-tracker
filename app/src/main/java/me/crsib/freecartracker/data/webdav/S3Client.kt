package me.crsib.freecartracker.data.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/** One object from a ListObjectsV2 page. */
data class S3Object(val key: String, val size: Long, val lastModifiedMillis: Long?)

/** A ListObjectsV2 result: [prefixes] are the "folders" rolled up by the delimiter. */
data class S3Listing(val prefixes: List<String>, val objects: List<S3Object>)

/**
 * Minimal S3 REST client over OkHttp, signing with AWS Signature V4 by hand so the app takes no
 * AWS SDK (which would dwarf the rest of the APK). Covers exactly what sync needs: GET, PUT, HEAD
 * and DELETE of an object, and ListObjectsV2. Works against AWS and the S3-compatible stores.
 *
 * Errors read `METHOD key -> status [S3Code]`, the same shape as [WebDavClient], so the worker's
 * "401/403 is permanent" check covers both backends.
 */
class S3Client(
    private val client: OkHttpClient = WebDavClient.defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Same long-timeout split as [WebDavClient]: trip payloads run to tens of megabytes. */
    private val largeTransferClient: OkHttpClient = WebDavClient.largeTransfer(client)

    /** GET an object, or null if there is no such key. */
    suspend fun get(config: S3Config, key: String, large: Boolean = false): Result<ByteArray?> =
        withContext(Dispatchers.IO) {
            runCatching {
                http(large).newCall(signed(config, "GET", key)).execute().use { r ->
                    if (r.code == 404) null else { r.ensureOk("GET", key); r.body?.bytes() ?: ByteArray(0) }
                }
            }
        }

    suspend fun put(
        config: S3Config,
        key: String,
        bytes: ByteArray,
        contentType: String,
        large: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            http(large).newCall(signed(config, "PUT", key, body = bytes, contentType = contentType)).execute()
                .use { r -> r.ensureOk("PUT", key) }
        }
    }

    /** True if [key] exists (HEAD; 404 -> false). */
    suspend fun head(config: S3Config, key: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(signed(config, "HEAD", key)).execute().use { r ->
                if (r.code == 404) false else { r.ensureOk("HEAD", key); true }
            }
        }
    }

    /** DELETE one object. S3 answers 204 whether or not it existed; a 404 is tolerated too. */
    suspend fun delete(config: S3Config, key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(signed(config, "DELETE", key)).execute().use { r ->
                if (r.code != 404) r.ensureOk("DELETE", key)
            }
        }
    }

    /**
     * ListObjectsV2 under [prefix]. With the default `/` [delimiter] this is one folder level;
     * pass null to list every key beneath it. Follows continuation tokens to the end unless
     * [maxKeys] is given, in which case only the first page is fetched.
     */
    suspend fun list(
        config: S3Config,
        prefix: String,
        delimiter: String? = "/",
        maxKeys: Int? = null,
    ): Result<S3Listing> = withContext(Dispatchers.IO) {
        runCatching {
            val prefixes = ArrayList<String>()
            val objects = ArrayList<S3Object>()
            var token: String? = null
            do {
                val query = buildList {
                    add("list-type" to "2")
                    add("prefix" to prefix)
                    delimiter?.let { add("delimiter" to it) }
                    maxKeys?.let { add("max-keys" to it.toString()) }
                    token?.let { add("continuation-token" to it) }
                }
                val page = client.newCall(signed(config, "GET", "", query)).execute().use { r ->
                    r.ensureOk("LIST", prefix.ifEmpty { "/" })
                    parseListing(r.body?.bytes() ?: ByteArray(0))
                }
                prefixes += page.listing.prefixes
                objects += page.listing.objects
                token = page.nextToken.takeIf { maxKeys == null }
            } while (token != null)
            S3Listing(prefixes, objects)
        }
    }

    private fun http(large: Boolean) = if (large) largeTransferClient else client

    /** Build a SigV4-signed request for [key] (empty = the bucket itself). */
    private fun signed(
        config: S3Config,
        method: String,
        key: String,
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): Request {
        val endpoint = config.effectiveEndpoint.toHttpUrl()
        val bucket = config.bucket.trim()
        val host = if (config.pathStyle) endpoint.host else "$bucket.${endpoint.host}"
        val segments = buildList {
            if (config.pathStyle) add(bucket)
            key.split('/').filter { it.isNotEmpty() }.forEach(::add)
        }
        val canonicalUri = "/" + segments.joinToString("/") { S3Signer.uriEncode(it) }
        val canonicalQuery = S3Signer.canonicalQuery(query)
        // Built from the already-encoded parts, so what goes on the wire is exactly what was signed.
        val url = HttpUrl.Builder()
            .scheme(endpoint.scheme)
            .host(host)
            .port(endpoint.port)
            .encodedPath(canonicalUri)
            .apply { if (canonicalQuery.isNotEmpty()) encodedQuery(canonicalQuery) }
            .build()
        val hostHeader = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"

        val payload = body ?: ByteArray(0)
        val payloadHash = S3Signer.sha256Hex(payload)
        val now = Instant.ofEpochMilli(clock())
        val amzDate = S3Signer.AMZ_DATE.format(now)
        val authorization = S3Signer.authorization(
            method = method,
            canonicalUri = canonicalUri,
            canonicalQuery = canonicalQuery,
            hostHeader = hostHeader,
            payloadHash = payloadHash,
            amzDate = amzDate,
            region = config.effectiveRegion,
            accessKeyId = config.accessKeyId.trim(),
            secretAccessKey = config.secretAccessKey.trim(),
        )
        val requestBody = when {
            body != null -> body.toRequestBody(contentType?.toMediaType())
            else -> null
        }
        return Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header("Authorization", authorization)
            .build()
    }

    private fun Response.ensureOk(method: String, key: String) {
        if (isSuccessful) return
        // S3 explains itself in the body (<Code>SignatureDoesNotMatch</Code>, NoSuchBucket, …),
        // and that code is what tells a user which field of the form they got wrong.
        val code = runCatching { body?.string() }.getOrNull()
            ?.let { Regex("<Code>([^<]+)</Code>").find(it)?.groupValues?.get(1) }
        error("$method $key -> ${this.code}" + (code?.let { " $it" } ?: ""))
    }

    private class Page(val listing: S3Listing, val nextToken: String?)

    private fun parseListing(xml: ByteArray): Page {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml))
        fun Element.child(name: String): String? = getElementsByTagName(name).item(0)?.textContent

        val prefixes = ArrayList<String>()
        val commonPrefixes = doc.getElementsByTagName("CommonPrefixes")
        for (i in 0 until commonPrefixes.length) {
            (commonPrefixes.item(i) as Element).child("Prefix")?.let(prefixes::add)
        }
        val objects = ArrayList<S3Object>()
        val contents = doc.getElementsByTagName("Contents")
        for (i in 0 until contents.length) {
            val c = contents.item(i) as Element
            val key = c.child("Key") ?: continue
            objects += S3Object(
                key = key,
                size = c.child("Size")?.trim()?.toLongOrNull() ?: 0L,
                lastModifiedMillis = c.child("LastModified")
                    ?.let { runCatching { Instant.parse(it.trim()).toEpochMilli() }.getOrNull() },
            )
        }
        val root = doc.documentElement
        val truncated = root.child("IsTruncated")?.trim() == "true"
        return Page(S3Listing(prefixes, objects), if (truncated) root.child("NextContinuationToken") else null)
    }
}

/** AWS Signature Version 4, for the S3 service. Pure functions, so they test against AWS's own vectors. */
internal object S3Signer {
    val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private const val SIGNED_HEADERS = "host;x-amz-content-sha256;x-amz-date"

    fun authorization(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        hostHeader: String,
        payloadHash: String,
        amzDate: String,
        region: String,
        accessKeyId: String,
        secretAccessKey: String,
    ): String {
        val dateStamp = amzDate.substring(0, 8)
        val canonicalHeaders = "host:$hostHeader\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val canonicalRequest =
            listOf(method, canonicalUri, canonicalQuery, canonicalHeaders, SIGNED_HEADERS, payloadHash).joinToString("\n")
        val scope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"

        var key = hmac("AWS4$secretAccessKey".toByteArray(), dateStamp)
        key = hmac(key, region)
        key = hmac(key, "s3")
        key = hmac(key, "aws4_request")
        val signature = hmac(key, stringToSign).toHex()
        return "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$SIGNED_HEADERS, Signature=$signature"
    }

    /** Query string in SigV4 canonical form: every name and value encoded, sorted by name then value. */
    fun canonicalQuery(params: List<Pair<String, String>>): String =
        params.map { (k, v) -> uriEncode(k) to uriEncode(v) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (k, v) -> "$k=$v" }

    /** RFC 3986 encoding as SigV4 defines it: everything but `A-Za-z0-9-_.~` is percent-encoded. */
    fun uriEncode(value: String): String = buildString {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                append(ch)
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
            }
        }
    }

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val HEX = "0123456789ABCDEF".toCharArray()
}

/**
 * [RemoteStorage] over an S3 bucket. The sync layout's folders become key prefixes under
 * [S3Config.prefix]; S3 has no real folders, so [ensureCollection] has nothing to do and a
 * recursive delete is a listing followed by one DELETE per key.
 */
class S3Storage(private val client: S3Client, private val config: S3Config) : RemoteStorage {
    private val root: String = config.prefix.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

    private fun key(relPath: String): String = root + relPath.trimStart('/')

    private fun folderKey(relPath: String): String =
        key(relPath).let { if (it.isEmpty() || it.endsWith('/')) it else "$it/" }

    /** One cheap listing of the root proves the endpoint, bucket, region and both keys at once. */
    override suspend fun testConnection(): Result<Unit> = client.list(config, root, maxKeys = 1).map { }

    override suspend fun ensureCollection(relPath: String, created: MutableSet<String>?): Result<Unit> =
        Result.success(Unit)

    override suspend fun list(relPath: String): Result<List<DavEntry>> {
        val prefix = folderKey(relPath)
        return client.list(config, prefix).map { listing ->
            val folders = listing.prefixes
                .map { it.removePrefix(prefix).trimEnd('/') }
                .filter { it.isNotEmpty() }
                .map { DavEntry(name = it, isCollection = true) }
            // A key equal to the prefix is a console-made "folder" placeholder, not a child.
            val files = listing.objects
                .filter { it.key != prefix }
                .map { DavEntry(name = it.key.removePrefix(prefix), isCollection = false, lastModifiedMillis = it.lastModifiedMillis) }
            folders + files
        }
    }

    override suspend fun exists(relPath: String): Result<Boolean> =
        if (relPath.endsWith('/')) {
            client.list(config, folderKey(relPath), delimiter = null, maxKeys = 1).map { it.objects.isNotEmpty() }
        } else {
            client.head(config, key(relPath))
        }

    override suspend fun putBytes(relPath: String, bytes: ByteArray): Result<Unit> =
        client.put(config, key(relPath), bytes, "application/json")

    override suspend fun putGzipped(relPath: String, gz: ByteArray): Result<Unit> =
        client.put(config, key(relPath), gz, "application/gzip", large = true)

    override suspend fun getBytes(relPath: String): Result<ByteArray?> = client.get(config, key(relPath))

    override suspend fun getGunzip(relPath: String): Result<ByteArray?> =
        client.get(config, key(relPath), large = true).map { it?.let(::gunzip) }

    override suspend fun delete(relPath: String): Result<Unit> =
        if (relPath.endsWith('/')) {
            runCatching {
                client.list(config, folderKey(relPath), delimiter = null).getOrThrow()
                    .objects.forEach { client.delete(config, it.key).getOrThrow() }
            }
        } else {
            client.delete(config, key(relPath))
        }
}

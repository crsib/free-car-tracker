package me.crsib.freecartracker.data.webdav

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class S3ClientTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: S3Storage

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val config = S3Config(
            endpoint = server.url("/").toString(),
            region = "eu-central-1",
            bucket = "cars",
            prefix = "/CarTracker/",
            accessKeyId = "AKID",
            secretAccessKey = "secret",
        )
        storage = S3Storage(S3Client(clock = { 1_369_353_600_000 }), config) // 2013-05-24T00:00:00Z
    }

    @After
    fun tearDown() = server.shutdown()

    /**
     * The "GET Bucket (List Objects)" example from the AWS SigV4 documentation
     * (sig-v4-header-based-auth): if this matches, the signer is byte-for-byte what S3 computes.
     */
    @Test
    fun signer_matchesAwsDocumentedExample() {
        val auth = S3Signer.authorization(
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = S3Signer.canonicalQuery(listOf("prefix" to "J", "max-keys" to "2")),
            hostHeader = "examplebucket.s3.amazonaws.com",
            payloadHash = S3Signer.sha256Hex(ByteArray(0)),
            amzDate = "20130524T000000Z",
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            auth,
        )
    }

    @Test
    fun uriEncode_leavesOnlyUnreservedCharacters() {
        assertEquals("a-b_c.d~e", S3Signer.uriEncode("a-b_c.d~e"))
        assertEquals("a%2Fb%20c%3D%2B", S3Signer.uriEncode("a/b c=+"))
    }

    @Test
    fun list_mapsPrefixesToFolders_underTheConfiguredPrefix() = runBlocking {
        server.enqueue(MockResponse().setBody(listing(prefixes = listOf("CarTracker/veh-1/", "CarTracker/did-scans/"), keys = listOf("CarTracker/", "CarTracker/readme.txt"))))

        val entries = storage.list("").getOrThrow()

        assertEquals(setOf("veh-1", "did-scans", "readme.txt"), entries.map { it.name }.toSet())
        assertTrue(entries.first { it.name == "veh-1" }.isCollection)
        assertFalse(entries.first { it.name == "readme.txt" }.isCollection)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        // Path style, sorted canonical query: exactly what was signed goes on the wire.
        assertEquals("/cars?delimiter=%2F&list-type=2&prefix=CarTracker%2F", req.path)
        assertEquals("20130524T000000Z", req.getHeader("x-amz-date"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("AWS4-HMAC-SHA256 Credential=AKID/20130524/eu-central-1/s3/aws4_request"))
    }

    @Test
    fun list_followsContinuationTokens() = runBlocking {
        server.enqueue(MockResponse().setBody(listing(prefixes = listOf("CarTracker/veh-1/trips/2026/"), next = "tok+/=")))
        server.enqueue(MockResponse().setBody(listing(prefixes = listOf("CarTracker/veh-1/trips/2025/"))))

        val names = storage.list("veh-1/trips/").getOrThrow().map { it.name }

        assertEquals(listOf("2026", "2025"), names)
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("continuation-token=tok%2B%2F%3D"))
    }

    @Test
    fun getBytes_nullOn404_andObjectKeyIsPrefixed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchKey</Code></Error>"))
        assertNull(storage.getBytes("veh-1/ledger.json").getOrThrow())
        assertEquals("/cars/CarTracker/veh-1/ledger.json", server.takeRequest().path)
    }

    @Test
    fun errors_carryStatusAndS3Code() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>SignatureDoesNotMatch</Code></Error>"))
        val e = storage.putBytes("veh-1/vehicle.json", "{}".toByteArray()).exceptionOrNull()!!
        assertEquals("PUT CarTracker/veh-1/vehicle.json -> 403 SignatureDoesNotMatch", e.message)
    }

    @Test
    fun putGzip_sendsSignedGzippedBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val plain = "timestamp,speed\n1,2\n".toByteArray()

        storage.putGzip("veh-1/trips/2026/06/t1/obd.csv.gz", plain).getOrThrow()

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/cars/CarTracker/veh-1/trips/2026/06/t1/obd.csv.gz", req.path)
        val body = req.body.readByteArray()
        assertEquals(S3Signer.sha256Hex(body), req.getHeader("x-amz-content-sha256"))
        assertEquals(plain.decodeToString(), GZIPInputStream(ByteArrayInputStream(body)).readBytes().decodeToString())
    }

    @Test
    fun getGunzip_decompresses() = runBlocking {
        val gz = Buffer().write(java.io.ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write("<gpx/>".toByteArray()) } }.toByteArray())
        server.enqueue(MockResponse().setBody(gz))
        assertEquals("<gpx/>", storage.getGunzip("veh-1/t/track.gpx.gz").getOrThrow()!!.decodeToString())
    }

    @Test
    fun exists_usesHead() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(storage.exists("veh-1/trips/2026/06/t1/trip.json").getOrThrow())
        assertFalse(storage.exists("veh-1/trips/2026/06/t2/trip.json").getOrThrow())
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun deleteFolder_listsRecursivelyThenDeletesEachKey() = runBlocking {
        val dir = "CarTracker/veh-1/trips/2026/06/t1/"
        server.enqueue(MockResponse().setBody(listing(keys = listOf("${dir}trip.json", "${dir}obd.csv.gz"))))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        storage.delete("veh-1/trips/2026/06/t1/").getOrThrow()

        val list = server.takeRequest()
        assertFalse("recursive listing has no delimiter", list.path!!.contains("delimiter"))
        assertEquals("DELETE" to "/cars/${dir}trip.json", server.takeRequest().let { it.method to it.path })
        assertEquals("DELETE" to "/cars/${dir}obd.csv.gz", server.takeRequest().let { it.method to it.path })
    }

    @Test
    fun ensureCollection_isANoOp() = runBlocking {
        storage.ensureCollection("veh-1/trips/2026/06/").getOrThrow()
        assertEquals(0, server.requestCount)
    }

    private fun listing(
        prefixes: List<String> = emptyList(),
        keys: List<String> = emptyList(),
        next: String? = null,
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>cars</Name><Prefix>x</Prefix>""")
        append("<IsTruncated>${next != null}</IsTruncated>")
        next?.let { append("<NextContinuationToken>$it</NextContinuationToken>") }
        keys.forEach {
            append("<Contents><Key>$it</Key><LastModified>2026-06-01T10:00:00.000Z</LastModified><Size>3</Size></Contents>")
        }
        prefixes.forEach { append("<CommonPrefixes><Prefix>$it</Prefix></CommonPrefixes>") }
        append("</ListBucketResult>")
    }
}

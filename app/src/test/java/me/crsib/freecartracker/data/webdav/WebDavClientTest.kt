package me.crsib.freecartracker.data.webdav

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    private lateinit var config: WebDavConfig

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = WebDavClient()
        config = WebDavConfig(url = server.url("/dav/").toString(), username = "u", password = "p")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun ensureCollection_createsEachSegmentInOrder_tolerating405() = runBlocking {
        // 201 (created), 405 (already exists), 201 — all treated as success.
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(201))

        client.ensureCollection(config, "veh-1/trips/2026/").getOrThrow()

        val paths = (1..3).map { server.takeRequest().let { r -> r.method to r.path } }
        assertEquals("MKCOL" to "/dav/veh-1/", paths[0])
        assertEquals("MKCOL" to "/dav/veh-1/trips/", paths[1])
        assertEquals("MKCOL" to "/dav/veh-1/trips/2026/", paths[2])
    }

    @Test
    fun list_parsesDepth1_excludesSelf_andFlagsCollections() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(NEXTCLOUD_MULTISTATUS))

        val entries = client.list(config, "veh-1/trips").getOrThrow()

        // self ("/dav/veh-1/trips/") excluded; one dir + one file remain.
        assertEquals(setOf("2026", "trip.json"), entries.map { it.name }.toSet())
        assertTrue(entries.first { it.name == "2026" }.isCollection)
        assertFalse(entries.first { it.name == "trip.json" }.isCollection)

        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("1", req.getHeader("Depth"))
        assertEquals("/dav/veh-1/trips/", req.path)
    }

    @Test
    fun exists_trueOn207_falseOn404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(NEXTCLOUD_MULTISTATUS))
        assertTrue(client.exists(config, "veh-1/vehicle.json").getOrThrow())

        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(client.exists(config, "veh-1/missing.json").getOrThrow())
    }

    @Test
    fun getBytes_returnsNullOn404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.getBytes(config, "veh-1/missing.json").getOrThrow())
    }

    @Test
    fun putGzip_sendsGzippedBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        val plain = "speed,rpm\n100,3000\n".toByteArray()

        client.putGzip(config, "veh-1/trips/2026/06/t1/obd.csv.gz", plain).getOrThrow()

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/dav/veh-1/trips/2026/06/t1/obd.csv.gz", req.path)
        val received = req.body.readByteArray()
        val decompressed = GZIPInputStream(ByteArrayInputStream(received)).use { it.readBytes() }
        assertEquals(String(plain), String(decompressed))
    }

    @Test
    fun getGunzip_decompressesResponse() = runBlocking {
        val plain = "lat,lon\n1.0,2.0\n"
        val gz = okio.Buffer().also { buf ->
            java.util.zip.GZIPOutputStream(buf.outputStream()).use { it.write(plain.toByteArray()) }
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(gz))

        val out = client.getGunzip(config, "veh-1/trips/2026/06/t1/track.gpx.gz").getOrThrow()
        assertEquals(plain, out?.let { String(it) })
    }

    @Test
    fun putBytes_andGetBytes_roundTripPlainJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        client.putBytes(config, "veh-1/vehicle.json", "{}".toByteArray()).getOrThrow()
        assertEquals("/dav/veh-1/vehicle.json", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"a\":1}"))
        val got = client.getBytes(config, "veh-1/vehicle.json").getOrThrow()
        assertEquals("{\"a\":1}", got?.let { String(it) })
    }

    private companion object {
        // A realistic Nextcloud-style multistatus for collection /dav/veh-1/trips/ with a
        // subdir (2026) and a file (trip.json). Uses the `d:` prefix and absolute hrefs.
        val NEXTCLOUD_MULTISTATUS = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/veh-1/trips/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
                <d:getlastmodified>Mon, 22 Jun 2026 10:00:00 GMT</d:getlastmodified></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/veh-1/trips/2026/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/veh-1/trips/trip.json</d:href>
                <d:propstat><d:prop><d:resourcetype/>
                <d:getlastmodified>Mon, 22 Jun 2026 11:00:00 GMT</d:getlastmodified></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}

package me.crsib.freecartracker.data.webdav

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.sync.TripFileManifest
import me.crsib.freecartracker.data.sync.TripManifest
import me.crsib.freecartracker.data.sync.VehicleDocument
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.entity.Trip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * [S3Storage] against a real S3-compatible server, which — unlike MockWebServer — actually checks
 * the signatures. Opt-in: set `FCT_S3_TEST_ENDPOINT`, `FCT_S3_TEST_BUCKET` (must exist),
 * `FCT_S3_TEST_ACCESS_KEY`, `FCT_S3_TEST_SECRET_KEY` (and optionally `FCT_S3_TEST_REGION`), e.g.
 * against a local MinIO/RustFS container; skipped otherwise.
 *
 * [writesALayoutTheViewerCanIndex] deliberately leaves one car with one trip under the
 * `viewer-fixture/` prefix, so the Go viewer can be pointed at the same bucket to confirm it reads
 * what the app writes.
 */
class S3StorageIntegrationTest {

    private lateinit var config: S3Config

    @Before
    fun setUp() {
        val endpoint = System.getenv("FCT_S3_TEST_ENDPOINT").orEmpty()
        assumeTrue("FCT_S3_TEST_ENDPOINT not set", endpoint.isNotEmpty())
        config = S3Config(
            endpoint = endpoint,
            region = System.getenv("FCT_S3_TEST_REGION") ?: S3Config.DEFAULT_REGION,
            bucket = System.getenv("FCT_S3_TEST_BUCKET").orEmpty(),
            accessKeyId = System.getenv("FCT_S3_TEST_ACCESS_KEY").orEmpty(),
            secretAccessKey = System.getenv("FCT_S3_TEST_SECRET_KEY").orEmpty(),
        )
    }

    private fun storage(prefix: String, cfg: S3Config = config) = S3Storage(S3Client(), cfg.copy(prefix = prefix))

    @Test
    fun roundTripsTheOperationsSyncUses() = runBlocking {
        val s = storage("it-${System.nanoTime()}")
        s.testConnection().getOrThrow()

        val dir = "veh-1/trips/2026/06/t1/"
        val csv = "timestamp,speed\n1,2\n".repeat(500).toByteArray()
        s.ensureCollection(dir).getOrThrow()
        s.putGzip(dir + "obd.csv.gz", csv).getOrThrow()
        s.putBytes(dir + "trip.json", """{"ok":true}""".toByteArray()).getOrThrow()
        s.putBytes("veh-1/tombstones/t0", ByteArray(0)).getOrThrow()
        s.putBytes("veh-1/vehicle.json", "{}".toByteArray()).getOrThrow()

        assertTrue(s.exists(dir + "trip.json").getOrThrow())
        assertFalse(s.exists(dir + "missing.json").getOrThrow())
        assertTrue(s.exists(dir).getOrThrow())
        assertEquals(listOf("veh-1"), s.list("").getOrThrow().filter { it.isCollection }.map { it.name })
        assertEquals(
            setOf("trips/", "tombstones/", "vehicle.json"),
            s.list("veh-1/").getOrThrow().map { if (it.isCollection) it.name + "/" else it.name }.toSet(),
        )
        assertEquals(listOf("t0"), s.list("veh-1/tombstones/").getOrThrow().map { it.name })
        assertTrue(s.list("nothing-here/").getOrThrow().isEmpty())

        assertEquals("""{"ok":true}""", s.getBytes(dir + "trip.json").getOrThrow()!!.decodeToString())
        assertArrayEquals(csv, s.getGunzip(dir + "obd.csv.gz").getOrThrow())
        assertNull(s.getBytes(dir + "missing.json").getOrThrow())

        s.delete(dir).getOrThrow()
        assertFalse(s.exists(dir + "trip.json").getOrThrow())
        assertTrue(s.list("veh-1/trips/2026/06/").getOrThrow().isEmpty())
        s.delete("veh-1/").getOrThrow()
        assertTrue(s.list("").getOrThrow().isEmpty())
    }

    @Test
    fun wrongSecretIsAPermanent403() = runBlocking {
        val e = storage("", config.copy(secretAccessKey = "wrong")).testConnection().exceptionOrNull()
        assertTrue("got ${e?.message}", e?.message.orEmpty().contains("-> 403"))
    }

    @Test
    fun writesALayoutTheViewerCanIndex() = runBlocking {
        val s = storage("viewer-fixture")
        val json = Json { encodeDefaults = true }
        val vSync = "11111111-2222-3333-4444-555555555555"
        val tSync = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val started = 1_780_000_000_000L
        val vehicle = Vehicle(id = 1, name = "S3 Fixture", powertrain = Powertrain.EV, createdAt = 0, syncId = vSync)
        val trip = Trip(
            id = 1, vehicleId = 1, startedAtMillis = started, endedAtMillis = started + 600_000,
            status = TripStatus.COMPLETED, distanceKm = 7.5, syncId = tSync,
        )
        s.putBytes(WebDavPaths.vehicleJson(vSync), json.encodeToString(VehicleDocument.serializer(), VehicleDocument(exportedAt = started, vehicle = vehicle)).toByteArray()).getOrThrow()
        s.putBytes(
            WebDavPaths.tripFile(vSync, started, tSync, WebDavPaths.TRIP_JSON),
            json.encodeToString(
                TripManifest.serializer(),
                TripManifest(tripSyncId = tSync, vehicleSyncId = vSync, trip = trip, files = TripFileManifest()),
            ).toByteArray(),
        ).getOrThrow()
        assertEquals(listOf(vSync), s.list("").getOrThrow().map { it.name })
    }
}

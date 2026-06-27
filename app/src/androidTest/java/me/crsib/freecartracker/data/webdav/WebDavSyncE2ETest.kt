package me.crsib.freecartracker.data.webdav

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.crsib.freecartracker.data.AppDatabase
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.TelemetryRepository
import me.crsib.freecartracker.telemetry.data.entity.DtcRecord
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import me.crsib.freecartracker.telemetry.data.entity.Trip
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * End-to-end multi-device sync against a real WebDAV server (wsgidav). Start it on the host:
 *   python -m wsgidav.server.server_cli --config wsgidav.yaml   (root rw for test:test, port 8080)
 * The AVD reaches the host at 10.0.2.2. Skips if the server isn't reachable. AVD-only
 * (ANDROID_SERIAL=emulator-5554).
 */
@RunWith(AndroidJUnit4::class)
class WebDavSyncE2ETest {

    private lateinit var ctx: Context
    private lateinit var settings: WebDavSettingsStore
    private val baseUrl = "http://10.0.2.2:8080/"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        assumeTrue("wsgidav not reachable at $baseUrl", serverReachable())
        // Unique root subdir per run so reruns don't collide. Create it on the server first
        // (production assumes the configured root already exists, as testConnection verifies).
        val runId = "e2e-" + System.nanoTime()
        runBlocking {
            WebDavClient().ensureCollection(
                WebDavConfig(url = baseUrl, username = "test", password = "test"),
                "$runId/",
            ).getOrThrow()
        }
        settings = WebDavSettingsStore(ctx).apply {
            save(WebDavConfig(url = "$baseUrl$runId/", username = "test", password = "test", autoBackup = true))
        }
    }

    @After
    fun tearDown() {
        // best-effort: leave artifacts on the server; a fresh subdir is used each run.
    }

    private fun newDb() = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
        .allowMainThreadQueries().build()

    private fun repo(db: AppDatabase) = TelemetryRepository(
        db.tripDao(), db.gpsPointDao(), db.obdSampleDao(), db.obdExtendedSampleDao(), db.dtcDao(),
        vehicleDao = db.vehicleDao(), deletedTripDao = db.deletedTripDao(),
    )

    private fun manager(db: AppDatabase, statePrefs: String) = WebDavSyncManager(
        store = settings,
        syncState = SyncStateStore(ctx, statePrefs),
        client = WebDavClient(),
        db = db,
        telemetryRepo = repo(db),
    )

    @Test
    fun headUnitUploads_phonePullsEverything() = runBlocking {
        val deviceA = newDb()
        val deviceB = newDb()
        try {
            val mgrA = manager(deviceA, "state_a_" + System.nanoTime())
            val mgrB = manager(deviceB, "state_b_" + System.nanoTime())

            // --- Device A: a car with a ledger entry and one completed trip ---
            val vehicleId = deviceA.vehicleDao().insert(
                Vehicle(name = "Model Y", powertrain = Powertrain.EV, createdAt = 0),
            )
            val vSync = deviceA.vehicleDao().getById(vehicleId)!!.syncId
            deviceA.fuelEntryDao().upsert(
                FuelEntry(vehicleId = vehicleId, dateMillis = 100, odometerKm = 1000.0, volumeL = 40.0, totalCostMinor = 5000, currency = "USD", isFullTank = true),
            )
            val tripId = deviceA.tripDao().insert(
                Trip(vehicleId = vehicleId, startedAtMillis = 1_700_000_000_000, endedAtMillis = 1_700_000_300_000, status = TripStatus.COMPLETED, distanceKm = 12.5),
            )
            val tSync = deviceA.tripDao().getById(tripId)!!.syncId
            deviceA.obdSampleDao().insertAll(listOf(
                ObdSample(tripId = tripId, timestampMillis = 1_700_000_000_000, speedKmh = 50.0, rpm = 0.0),
                ObdSample(tripId = tripId, timestampMillis = 1_700_000_000_100, speedKmh = 60.0),
            ))
            deviceA.obdExtendedSampleDao().insertAll(listOf(
                ObdExtendedSample(tripId = tripId, timestampMillis = 1_700_000_000_000, metricKey = "EV_SOC", value = 80.0, unit = "PCT"),
            ))
            deviceA.gpsPointDao().insertAll(listOf(
                GpsPoint(tripId = tripId, timestampMillis = 1_700_000_000_000, latitude = 37.0, longitude = -122.0, provider = "gps"),
            ))
            deviceA.dtcDao().insertAll(listOf(DtcRecord(tripId = tripId, timestampMillis = 1_700_000_000_500, code = "P0420")))

            val pushReport = mgrA.syncNow(now = 1000).getOrThrow()
            assertEquals(1, pushReport.ledgersPushed)
            assertEquals(1, pushReport.tripsUploaded)
            // Trip is now marked synced locally.
            assertNotNull(deviceA.tripDao().getById(tripId)!!.syncedAtMillis)

            // --- Device B: fresh, pulls the car + ledger + trip ---
            val pullReport = mgrB.syncNow(now = 2000).getOrThrow()
            assertEquals(1, pullReport.vehiclesPulled)
            assertEquals(1, pullReport.tripsImported)

            val vB = deviceB.vehicleDao().findBySyncId(vSync)
            assertNotNull(vB)
            assertEquals("Model Y", vB!!.name)
            assertEquals(1, deviceB.fuelEntryDao().getForVehicle(vB.id).size)

            val tB = deviceB.tripDao().findBySyncId(tSync)
            assertNotNull(tB)
            assertEquals(2, deviceB.obdSampleDao().getForTrip(tB!!.id).size)
            assertEquals(1, deviceB.obdExtendedSampleDao().getForTrip(tB.id).size)
            assertEquals(1, deviceB.gpsPointDao().getForTrip(tB.id).size)
            assertEquals(1, deviceB.dtcDao().getForTrip(tB.id).size)

            // --- Idempotency: A re-syncs, nothing new uploads ---
            val again = mgrA.syncNow(now = 3000).getOrThrow()
            assertEquals(0, again.tripsUploaded)

            // --- Ledger LWW: A edits ledger, B pulls the update ---
            deviceA.fuelEntryDao().upsert(
                FuelEntry(vehicleId = vehicleId, dateMillis = 200, odometerKm = 1200.0, volumeL = 38.0, totalCostMinor = 4800, currency = "USD", isFullTank = true),
            )
            mgrA.syncNow(now = 4000).getOrThrow()
            val pull2 = mgrB.syncNow(now = 5000).getOrThrow()
            assertEquals(1, pull2.ledgersPulled)
            assertEquals(2, deviceB.fuelEntryDao().getForVehicle(vB.id).size)
            assertTrue(pull2.tripsImported == 0)
        } finally {
            deviceA.close()
            deviceB.close()
        }
    }

    @Test
    fun deletingSyncedTrip_purgesRemote_andPropagatesToOtherDevice() = runBlocking {
        val deviceA = newDb()
        val deviceB = newDb()
        try {
            val mgrA = manager(deviceA, "del_a_" + System.nanoTime())
            val mgrB = manager(deviceB, "del_b_" + System.nanoTime())

            val vehicleId = deviceA.vehicleDao().insert(
                Vehicle(name = "Model 3", powertrain = Powertrain.EV, createdAt = 0),
            )
            val tripId = deviceA.tripDao().insert(
                Trip(vehicleId = vehicleId, startedAtMillis = 1_700_000_000_000, status = TripStatus.COMPLETED, distanceKm = 5.0),
            )
            val tSync = deviceA.tripDao().getById(tripId)!!.syncId
            deviceA.obdSampleDao().insertAll(listOf(ObdSample(tripId = tripId, timestampMillis = 1_700_000_000_000, speedKmh = 30.0)))

            // A pushes the trip; B pulls it.
            mgrA.syncNow(now = 1000).getOrThrow()
            assertEquals(1, mgrB.syncNow(now = 2000).getOrThrow().tripsImported)
            assertNotNull(deviceB.tripDao().findBySyncId(tSync))

            // A deletes the synced trip → tombstone, then sync purges the remote folder.
            repo(deviceA).deleteTrip(tripId)
            val delReport = mgrA.syncNow(now = 3000).getOrThrow()
            assertEquals(1, delReport.tripsDeleted)
            assertNull("A must not re-import its own deleted trip", deviceA.tripDao().findBySyncId(tSync))

            // A re-syncs: the remote folder is gone and the tombstone guards re-import.
            val reSync = mgrA.syncNow(now = 4000).getOrThrow()
            assertEquals(0, reSync.tripsImported)
            assertNull(deviceA.tripDao().findBySyncId(tSync))

            // B pulls the tombstone and drops its local copy.
            val bPull = mgrB.syncNow(now = 5000).getOrThrow()
            assertEquals(1, bPull.tripsRemoved)
            assertNull(deviceB.tripDao().findBySyncId(tSync))

            // Idempotent on B: nothing left to remove or import.
            val bAgain = mgrB.syncNow(now = 6000).getOrThrow()
            assertEquals(0, bAgain.tripsRemoved)
            assertEquals(0, bAgain.tripsImported)
        } finally {
            deviceA.close()
            deviceB.close()
        }
    }

    private fun serverReachable(): Boolean = try {
        val req = okhttp3.Request.Builder().url(baseUrl)
            .header("Authorization", okhttp3.Credentials.basic("test", "test"))
            .method("PROPFIND", null).header("Depth", "0").build()
        OkHttpClient().newCall(req).execute().use { it.isSuccessful || it.code == 207 }
    } catch (e: IOException) {
        false
    }
}

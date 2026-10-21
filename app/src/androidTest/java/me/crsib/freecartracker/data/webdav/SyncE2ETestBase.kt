package me.crsib.freecartracker.data.webdav

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end multi-device sync against a real server: two in-memory databases stand in for the
 * head unit and the phone, each with its own [WebDavSyncManager], sharing one remote folder.
 * Subclasses pick the backend ([WebDavSyncE2ETest], [S3SyncE2ETest]) and skip when its server
 * is not reachable from the AVD (the host is 10.0.2.2). AVD-only (ANDROID_SERIAL=emulator-5554).
 */
abstract class SyncE2ETestBase {

    protected lateinit var ctx: Context
    private lateinit var settings: WebDavSettingsStore

    /**
     * Prepare a fresh remote folder unique to [runId], so reruns never collide, and return the
     * config pointing at it. Call `assumeTrue` here when the server is unreachable.
     */
    protected abstract fun prepareRemote(runId: String): WebDavConfig

    protected companion object {
        /**
         * One client for every simulated device, as the app has one per process. A client per
         * manager leaves a pool of idle keep-alive connections behind each one, and a small test
         * server (wsgidav pins a worker thread per open connection) runs out of threads a few
         * tests in, failing later ones with truncated responses.
         */
        val sharedWebDav = WebDavClient()
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val config = prepareRemote("e2e-" + System.nanoTime())
        settings = WebDavSettingsStore(ctx).apply { save(config) }
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
        client = sharedWebDav,
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

    @Test
    fun switchingTarget_reuploadsTripsSyncedToTheOldOne() = runBlocking {
        val deviceA = newDb()
        val deviceB = newDb()
        try {
            val mgrA = manager(deviceA, "move_a_" + System.nanoTime())

            val vehicleId = deviceA.vehicleDao().insert(
                Vehicle(name = "Model S", powertrain = Powertrain.EV, createdAt = 0),
            )
            val tripId = deviceA.tripDao().insert(
                Trip(vehicleId = vehicleId, startedAtMillis = 1_700_000_000_000, status = TripStatus.COMPLETED, distanceKm = 9.0),
            )
            val tSync = deviceA.tripDao().getById(tripId)!!.syncId
            deviceA.obdSampleDao().insertAll(listOf(ObdSample(tripId = tripId, timestampMillis = 1_700_000_000_000, speedKmh = 40.0)))

            assertEquals(1, mgrA.syncNow(now = 1000).getOrThrow().tripsUploaded)
            assertEquals(0, mgrA.syncNow(now = 2000).getOrThrow().tripsUploaded)

            // Point the same device at a brand-new, empty folder: the old upload marks must not
            // stop the trip from reaching it.
            settings.save(prepareRemote("e2e-moved-" + System.nanoTime()))
            assertEquals(1, mgrA.syncNow(now = 3000).getOrThrow().tripsUploaded)
            assertEquals(0, mgrA.syncNow(now = 4000).getOrThrow().tripsUploaded)

            // A fresh device on the new target gets the trip with its samples.
            val pulled = manager(deviceB, "move_b_" + System.nanoTime()).syncNow(now = 5000).getOrThrow()
            assertEquals(1, pulled.tripsImported)
            val tB = deviceB.tripDao().findBySyncId(tSync)
            assertNotNull(tB)
            assertEquals(1, deviceB.obdSampleDao().getForTrip(tB!!.id).size)
        } finally {
            deviceA.close()
            deviceB.close()
        }
    }
}

package me.crsib.freecartracker.data.sync

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
import me.crsib.freecartracker.telemetry.data.entity.DtcRecord
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import me.crsib.freecartracker.telemetry.data.entity.Trip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SyncImporter round-trip against an in-memory Room DB (runs on the AVD). */
@RunWith(AndroidJUnit4::class)
class SyncImporterTest {

    private lateinit var db: AppDatabase
    private lateinit var importer: SyncImporter

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        importer = SyncImporter(db)
    }

    @After
    fun tearDown() = db.close()

    private fun vehicle(syncId: String, name: String) =
        Vehicle(name = name, powertrain = Powertrain.ICE, createdAt = 0, syncId = syncId)

    @Test
    fun importVehicle_insertsThenUpsertsBySyncId() = runBlocking {
        val id = importer.importVehicle(VehicleDocument(exportedAt = 1, vehicle = vehicle("v-1", "Car")))
        assertEquals(1, db.vehicleDao().getAll().size)

        // Same syncId, changed name -> update in place, no new row.
        val id2 = importer.importVehicle(VehicleDocument(exportedAt = 2, vehicle = vehicle("v-1", "Renamed")))
        assertEquals(id, id2)
        assertEquals(1, db.vehicleDao().getAll().size)
        assertEquals("Renamed", db.vehicleDao().findBySyncId("v-1")!!.name)
    }

    @Test
    fun mergeLedger_addsRemoteAndKeepsLocalOnly() = runBlocking {
        importer.importVehicle(VehicleDocument(exportedAt = 1, vehicle = vehicle("v-1", "Car")))
        val localId = db.vehicleDao().findBySyncId("v-1")!!.id

        // A local-only entry that the remote ledger doesn't know about (the regression: it must survive).
        db.fuelEntryDao().upsert(
            FuelEntry(
                vehicleId = localId, dateMillis = 50, odometerKm = 900.0, volumeL = 30.0,
                totalCostMinor = 4000, currency = "USD", isFullTank = true,
                syncId = "local-only", updatedAtMillis = 50,
            ),
        )

        val remote = FuelEntry(
            vehicleId = 0, dateMillis = 100, odometerKm = 1000.0, volumeL = 40.0,
            totalCostMinor = 5000, currency = "USD", isFullTank = true,
            syncId = "remote-1", updatedAtMillis = 100,
        )
        val ok = importer.mergeLedger(
            LedgerDocument(exportedAt = 2, vehicleSyncId = "v-1", fuelEntries = listOf(remote)),
        )
        assertTrue(ok)
        val stored = db.fuelEntryDao().getForVehicle(localId)
        assertEquals(2, stored.size) // union: local-only kept, remote added
        assertTrue(stored.any { it.syncId == "local-only" })
        assertTrue(stored.any { it.syncId == "remote-1" && it.vehicleId == localId })

        // Missing vehicle -> no-op false.
        assertFalse(importer.mergeLedger(LedgerDocument(exportedAt = 3, vehicleSyncId = "absent")))
    }

    @Test
    fun mergeLedger_appliesTombstonesAndNewerEdits() = runBlocking {
        importer.importVehicle(VehicleDocument(exportedAt = 1, vehicle = vehicle("v-1", "Car")))
        val localId = db.vehicleDao().findBySyncId("v-1")!!.id

        db.fuelEntryDao().upsert(
            FuelEntry(
                vehicleId = localId, dateMillis = 50, odometerKm = 900.0, volumeL = 30.0,
                totalCostMinor = 4000, currency = "USD", isFullTank = true,
                syncId = "doomed", updatedAtMillis = 50,
            ),
        )
        db.fuelEntryDao().upsert(
            FuelEntry(
                vehicleId = localId, dateMillis = 60, odometerKm = 950.0, volumeL = 20.0,
                totalCostMinor = 3000, currency = "USD", isFullTank = true,
                syncId = "edited", updatedAtMillis = 60,
            ),
        )

        importer.mergeLedger(
            LedgerDocument(
                exportedAt = 2, vehicleSyncId = "v-1",
                fuelEntries = listOf(
                    FuelEntry(
                        vehicleId = 0, dateMillis = 60, odometerKm = 950.0, volumeL = 25.0,
                        totalCostMinor = 3500, currency = "USD", isFullTank = true,
                        syncId = "edited", updatedAtMillis = 200, // newer edit wins
                    ),
                ),
                deletedEntries = listOf(LedgerTombstone(syncId = "doomed", deletedAtMillis = 100)),
            ),
        )

        val stored = db.fuelEntryDao().getForVehicle(localId)
        assertEquals(1, stored.size)
        assertEquals("edited", stored[0].syncId)
        assertEquals(25.0, stored[0].volumeL, 0.0) // remote edit applied
        assertTrue(db.deletedLedgerEntryDao().isTombstoned("doomed"))
    }

    @Test
    fun importTrip_insertsOnceAndDedupsBySyncId() = runBlocking {
        importer.importVehicle(VehicleDocument(exportedAt = 1, vehicle = vehicle("v-1", "Car")))

        val trip = Trip(
            vehicleId = 0, startedAtMillis = 1000, endedAtMillis = 5000, status = TripStatus.COMPLETED,
            distanceKm = 12.0, syncId = "trip-1",
        )
        val samples = listOf(ObdSample(tripId = 0, timestampMillis = 1000, speedKmh = 100.0, rpm = 3000.0))
        val points = listOf(
            GpsPoint(tripId = 0, timestampMillis = 1000, latitude = 1.0, longitude = 2.0, provider = "gps"),
        )
        val obdCsv = ObdCsvExporter.export(samples, emptyList())
        val gpx = GpxExporter.export(trip, points)
        val manifest = TripManifest(
            tripSyncId = "trip-1", vehicleSyncId = "v-1", trip = trip,
            dtcs = listOf(DtcRecord(tripId = 0, timestampMillis = 1200, code = "P0420")),
        )

        assertTrue(importer.importTrip(manifest, obdCsv, gpx, now = 9999))
        val stored = db.tripDao().findBySyncId("trip-1")
        assertNotNull(stored)
        assertEquals(9999L, stored!!.syncedAtMillis)
        assertEquals(1, db.obdSampleDao().getForTrip(stored.id).size)
        assertEquals(1, db.gpsPointDao().getForTrip(stored.id).size)
        assertEquals(1, db.dtcDao().getForTrip(stored.id).size)

        // Second import with the same syncId is a no-op.
        assertFalse(importer.importTrip(manifest, obdCsv, gpx, now = 12345))
        assertEquals(1, db.obdSampleDao().getForTrip(stored.id).size)
    }
}

package me.crsib.freecartracker.telemetry.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.crsib.freecartracker.data.AppDatabase
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.entity.Trip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The upload queue is a query, not an event: [me.crsib.freecartracker.telemetry.data.dao.TripDao.getUnsynced]
 * returns every COMPLETED trip whose `syncedAtMillis` is still null. That's what lets a trip finished
 * offline get pushed by whatever sync runs next — the foreground trigger, the daily worker, or (the
 * case these tests cover) the ConnectivityWatcher firing when the device comes back online. If this
 * contract held only for trips uploaded inline at finish time, an offline trip would be lost.
 */
@RunWith(AndroidJUnit4::class)
class TripUploadQueueTest {

    private lateinit var db: AppDatabase
    private var vehicleId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        vehicleId = db.vehicleDao().insert(
            Vehicle(name = "Model Y", powertrain = Powertrain.EV, createdAt = 0),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertTrip(status: TripStatus, syncedAtMillis: Long? = null): Long =
        db.tripDao().insert(
            Trip(
                vehicleId = vehicleId,
                startedAtMillis = 1_700_000_000_000,
                endedAtMillis = if (status == TripStatus.RECORDING) null else 1_700_000_300_000,
                status = status,
                syncedAtMillis = syncedAtMillis,
            ),
        )

    @Test
    fun completedUnsyncedTrip_isQueuedForUpload() = runBlocking {
        val id = insertTrip(TripStatus.COMPLETED)
        val queued = db.tripDao().getUnsynced(TripStatus.COMPLETED)
        assertEquals(listOf(id), queued.map { it.id })
    }

    @Test
    fun recordingTrip_isNotQueued() = runBlocking {
        // A trip still being recorded must not be uploaded — only finished ones.
        insertTrip(TripStatus.RECORDING)
        assertEquals(emptyList<Long>(), db.tripDao().getUnsynced(TripStatus.COMPLETED).map { it.id })
    }

    @Test
    fun markSynced_removesTripFromQueue() = runBlocking {
        // Simulates the trip uploading once connectivity returns: after markSynced it must not
        // upload again on the next sync (idempotency).
        val id = insertTrip(TripStatus.COMPLETED)
        assertEquals(1, db.tripDao().getUnsynced(TripStatus.COMPLETED).size)

        db.tripDao().markSynced(id, syncedAtMillis = 9_999)

        assertEquals(emptyList<Long>(), db.tripDao().getUnsynced(TripStatus.COMPLETED).map { it.id })
    }

    @Test
    fun offlineTrip_survivesAcrossSyncAttempts_untilOneSucceeds() = runBlocking {
        // A trip finished offline stays queued through any number of failed (offline) syncs, then
        // is picked up by the first one that runs to completion — the reconnect path's guarantee.
        val id = insertTrip(TripStatus.COMPLETED)

        // Failed syncs leave the row untouched: still queued.
        repeat(3) { assertEquals(listOf(id), db.tripDao().getUnsynced(TripStatus.COMPLETED).map { it.id }) }

        // First successful sync marks it.
        db.tripDao().markSynced(id, syncedAtMillis = 5_000)
        assertEquals(emptyList<Long>(), db.tripDao().getUnsynced(TripStatus.COMPLETED).map { it.id })
    }
}

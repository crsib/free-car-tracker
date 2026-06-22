package me.crsib.freecartracker.telemetry

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.crsib.freecartracker.data.AppDatabase
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.repository.VehicleRepository
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.TelemetryRepository
import me.crsib.freecartracker.telemetry.location.LocationSourceFactory
import me.crsib.freecartracker.telemetry.obd.transport.ObdConnectionConfig
import me.crsib.freecartracker.telemetry.obd.transport.TransportFactory
import me.crsib.freecartracker.telemetry.obd.transport.WifiTransport
import me.crsib.freecartracker.telemetry.service.RecordingState
import me.crsib.freecartracker.telemetry.service.TripRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the whole recording pipeline (connect -> poll -> buffer -> flush -> DB -> finalize)
 * against the Ircama ELM327 emulator over WiFi. Start it first: `python -m elm -n 35000 -s car`.
 * Skips if the emulator isn't reachable. Runs on the AVD only (ANDROID_SERIAL=emulator-5554).
 */
@RunWith(AndroidJUnit4::class)
class TripRecorderIntegrationTest {

    @Test
    fun recordsObdTripAndFinalizesWithAggregates() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // Skip unless the emulator is reachable from the AVD.
        val probe = WifiTransport(HOST, PORT, connectTimeoutMs = 1500)
        try {
            withTimeout(3000) { probe.connect() }
            probe.close()
        } catch (e: Exception) {
            assumeNoException("ELM327 emulator not reachable at $HOST:$PORT", e)
            return@runBlocking
        }

        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val vehicleRepo = VehicleRepository(db.vehicleDao())
            val telemetryRepo = TelemetryRepository(
                db.tripDao(), db.gpsPointDao(), db.obdSampleDao(), db.obdExtendedSampleDao(), db.dtcDao(),
            )
            val vehicleId = vehicleRepo.add(
                Vehicle(name = "IT Car", powertrain = Powertrain.ICE, createdAt = 0L),
            )

            val recorder = TripRecorder(
                telemetryRepo = telemetryRepo,
                vehicleRepo = vehicleRepo,
                transportFactory = TransportFactory(ctx),
                locationFactory = LocationSourceFactory(ctx),
            )
            val config = ObdConnectionConfig(kind = ObdTransportKind.WIFI, wifiHost = HOST, wifiPort = PORT)

            recorder.start(vehicleId, config)
            withTimeout(15_000) { recorder.recordingState.first { it == RecordingState.RECORDING } }
            delay(3_000) // let several poll cycles run
            recorder.stop()
            withTimeout(10_000) { recorder.recordingState.first { it == RecordingState.IDLE } }

            val trips = db.tripDao().observeForVehicle(vehicleId).first()
            assertEquals(1, trips.size)
            val trip = trips.first()
            assertEquals(TripStatus.COMPLETED, trip.status)
            assertTrue("expected recorded OBD samples, got ${trip.obdSampleCount}", trip.obdSampleCount > 0)
            assertTrue("expected non-null duration", (trip.durationSec ?: -1) >= 0)
        } finally {
            db.close()
        }
    }

    companion object {
        private const val HOST = "10.0.2.2"
        private const val PORT = 35000
    }
}

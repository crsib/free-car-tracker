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
import me.crsib.freecartracker.telemetry.obd.transport.ObdTransport
import me.crsib.freecartracker.telemetry.obd.transport.TransportFactory
import me.crsib.freecartracker.telemetry.service.RecordingState
import me.crsib.freecartracker.telemetry.service.TripRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the manufacturer (Mode 22) path end-to-end: a Hyundai EV selects the Mode 22 profile,
 * the recorder polls EV_SOC, and the decoded value lands in obd_extended_samples — all driven
 * by an in-memory [FakeObdTransport], no hardware or emulator needed.
 */
@RunWith(AndroidJUnit4::class)
class Mode22RecordingIntegrationTest {

    private val answers = mapOf(
        "0100" to "41 00 BE 3E B8 11", // supported PIDs mask (includes 0C, 0D)
        "010C" to "41 0C 1A F8", // RPM
        "010D" to "41 0D 50", // speed 80 km/h
        "220105" to "62 01 05 80", // Hyundai EV state of charge -> 0x80 -> ~50%
    )

    @Test
    fun recordsEvStateOfChargeIntoExtendedTable() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val vehicleRepo = VehicleRepository(db.vehicleDao())
            val telemetryRepo = TelemetryRepository(
                db.tripDao(), db.gpsPointDao(), db.obdSampleDao(), db.obdExtendedSampleDao(), db.dtcDao(),
            )
            val vehicleId = vehicleRepo.add(
                Vehicle(name = "Ioniq", powertrain = Powertrain.EV, make = "Hyundai", createdAt = 0L),
            )

            val fakeFactory = object : TransportFactory(ctx) {
                override fun create(config: ObdConnectionConfig): ObdTransport = FakeObdTransport(answers)
            }
            val recorder = TripRecorder(
                telemetryRepo = telemetryRepo,
                vehicleRepo = vehicleRepo,
                transportFactory = fakeFactory,
                locationFactory = LocationSourceFactory(ctx),
            )

            recorder.start(vehicleId, ObdConnectionConfig(kind = ObdTransportKind.WIFI))
            withTimeout(15_000) { recorder.recordingState.first { it == RecordingState.RECORDING } }
            delay(3_000) // several poll cycles so the SLOW EV_SOC PID is queried
            recorder.stop()
            withTimeout(10_000) { recorder.recordingState.first { it == RecordingState.IDLE } }

            val trip = db.tripDao().observeForVehicle(vehicleId).first().first()
            assertEquals(TripStatus.COMPLETED, trip.status)
            assertEquals("HYUNDAI_KIA", trip.profileKey)

            val extended = db.obdExtendedSampleDao().getForTrip(trip.id)
            val soc = extended.filter { it.metricKey == "EV_SOC" }
            assertTrue("expected EV_SOC extended samples, got ${extended.map { it.metricKey }}", soc.isNotEmpty())
            // 0x80 (128) -> 128*100/255 = 50.196..%
            assertEquals(50.196, soc.first().value, 0.1)
            assertEquals("%", soc.first().unit)
        } finally {
            db.close()
        }
    }
}

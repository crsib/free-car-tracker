package me.crsib.freecartracker.data.sync

import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.Trip
import org.junit.Assert.assertEquals
import org.junit.Test

class GpxRoundTripTest {

    @Test
    fun export_thenParse_restoresPoints() {
        val trip = Trip(vehicleId = 1, startedAtMillis = 0, syncId = "t")
        val points = listOf(
            GpsPoint(
                tripId = 9, timestampMillis = 1500, latitude = 37.123456, longitude = -122.654321,
                altitudeM = 12.3, speedKmh = 50.0, bearingDeg = 90f, accuracyM = 5f, provider = "fused",
            ),
            // Sub-set of fields: no altitude/bearing/accuracy; whole-second timestamp.
            GpsPoint(
                tripId = 9, timestampMillis = 2000, latitude = 1.0, longitude = 2.0, provider = "gps",
            ),
        )

        val gpx = GpxExporter.export(trip, points)
        val out = GpxParser.parse(gpx, tripId = 9)

        assertEquals(points, out)
    }
}

package me.crsib.freecartracker.data.sync

import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.Trip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {

    private val trip = Trip(vehicleId = 1, startedAtMillis = 0, syncId = "trip-uuid")

    @Test
    fun export_writesTrackpointsWithTimeAndExtensions() {
        val points = listOf(
            GpsPoint(
                tripId = 1, timestampMillis = 1000, latitude = 37.5, longitude = -122.3,
                altitudeM = 10.0, speedKmh = 50.0, bearingDeg = 90f, accuracyM = 5f, provider = "gps",
            ),
        )
        val gpx = GpxExporter.export(trip, points).decodeToString()

        assertTrue(gpx.contains("xmlns:fct=\"${GpxExporter.FCT_NS}\""))
        assertTrue(gpx.contains("<trkpt lat=\"37.5\" lon=\"-122.3\">"))
        assertTrue(gpx.contains("<ele>10</ele>"))
        assertTrue(gpx.contains("<time>1970-01-01T00:00:01Z</time>"))
        assertTrue(gpx.contains("<fct:speedKmh>50</fct:speedKmh>"))
        assertTrue(gpx.contains("<fct:bearingDeg>90</fct:bearingDeg>"))
        assertTrue(gpx.contains("<fct:provider>gps</fct:provider>"))
    }

    @Test
    fun export_omitsEleWhenAltitudeNull() {
        val points = listOf(
            GpsPoint(tripId = 1, timestampMillis = 0, latitude = 1.0, longitude = 2.0, provider = "network"),
        )
        val gpx = GpxExporter.export(trip, points).decodeToString()
        assertFalse(gpx.contains("<ele>"))
        assertTrue(gpx.contains("<fct:provider>network</fct:provider>"))
    }
}

package me.crsib.freecartracker.data.sync

import me.crsib.freecartracker.data.backup.num
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.Trip
import java.time.Instant

/**
 * Exports a trip's GPS track to GPX 1.1 (opens in any mapping tool). Per-fix fields with no
 * native GPX element — speed, bearing, accuracy, provider — go into a custom-namespace
 * `<extensions>` block so the round-trip is lossless ([GpxParser] reads them back).
 */
object GpxExporter {
    const val FCT_NS = "https://crsib.me/freecartracker/gpx/1"

    fun export(trip: Trip, points: List<GpsPoint>): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"free-car-tracker\" ")
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:fct=\"").append(FCT_NS).append("\">\n")
        sb.append("<trk><name>").append(xmlEscape("trip-${trip.syncId}")).append("</name><trkseg>\n")
        for (p in points) {
            sb.append("<trkpt lat=\"").append(num(p.latitude)).append("\" lon=\"").append(num(p.longitude)).append("\">")
            p.altitudeM?.let { sb.append("<ele>").append(num(it)).append("</ele>") }
            sb.append("<time>").append(Instant.ofEpochMilli(p.timestampMillis).toString()).append("</time>")
            sb.append("<extensions>")
            p.speedKmh?.let { sb.append("<fct:speedKmh>").append(num(it)).append("</fct:speedKmh>") }
            p.bearingDeg?.let { sb.append("<fct:bearingDeg>").append(num(it.toDouble())).append("</fct:bearingDeg>") }
            p.accuracyM?.let { sb.append("<fct:accuracyM>").append(num(it.toDouble())).append("</fct:accuracyM>") }
            sb.append("<fct:provider>").append(xmlEscape(p.provider)).append("</fct:provider>")
            sb.append("</extensions>")
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun xmlEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

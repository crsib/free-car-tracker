package me.crsib.freecartracker.data.sync

import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a [GpxExporter] track back into [GpsPoint]s. Namespace-aware so the GPX core fields and
 * the `fct:` extensions are read regardless of prefix. Missing extensions (e.g. a foreign GPX)
 * leave the nullable fields null; provider defaults to "gps".
 */
object GpxParser {
    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"

    fun parse(bytes: ByteArray, tripId: Long): List<GpsPoint> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val trkpts = doc.getElementsByTagNameNS(GPX_NS, "trkpt")
        val out = ArrayList<GpsPoint>(trkpts.length)
        for (i in 0 until trkpts.length) {
            val el = trkpts.item(i) as Element
            val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
            val time = childText(el, GPX_NS, "time")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: continue
            out += GpsPoint(
                tripId = tripId,
                timestampMillis = time,
                latitude = lat,
                longitude = lon,
                altitudeM = childText(el, GPX_NS, "ele")?.toDoubleOrNull(),
                speedKmh = childText(el, GpxExporter.FCT_NS, "speedKmh")?.toDoubleOrNull(),
                bearingDeg = childText(el, GpxExporter.FCT_NS, "bearingDeg")?.toFloatOrNull(),
                accuracyM = childText(el, GpxExporter.FCT_NS, "accuracyM")?.toFloatOrNull(),
                provider = childText(el, GpxExporter.FCT_NS, "provider") ?: "gps",
            )
        }
        return out
    }

    private fun childText(parent: Element, ns: String, local: String): String? =
        parent.getElementsByTagNameNS(ns, local).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

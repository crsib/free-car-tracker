package me.crsib.freecartracker.data.sync

import me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Test

class ObdCsvRoundTripTest {

    @Test
    fun export_thenParse_restoresSamplesAndExtended() {
        val samples = listOf(
            ObdSample(tripId = 7, timestampMillis = 1000, speedKmh = 100.0, rpm = 3000.0, coolantTempC = 88.0),
            ObdSample(tripId = 7, timestampMillis = 2000, speedKmh = 0.0, runTimeSec = 120),
        )
        val extended = listOf(
            ObdExtendedSample(tripId = 7, timestampMillis = 1000, metricKey = "EV_SOC", value = 80.5, unit = "PCT"),
            ObdExtendedSample(tripId = 7, timestampMillis = 2000, metricKey = "MOTOR_TEMP", value = 45.0, unit = "C"),
        )

        val csv = ObdCsvExporter.export(samples, extended)
        val (outSamples, outExtended) = ObdCsvParser.parse(csv, tripId = 7)

        assertEquals(samples.sortedBy { it.timestampMillis }, outSamples.sortedBy { it.timestampMillis })
        assertEquals(
            extended.sortedWith(compareBy({ it.timestampMillis }, { it.metricKey })),
            outExtended.sortedWith(compareBy({ it.timestampMillis }, { it.metricKey })),
        )
    }

    @Test
    fun parse_isHeaderDriven_toleratesReorderAndUnknownColumns() {
        // Reordered columns, an extended column before a standard one, and an unknown future column.
        val csv = """
            timestampMillis,EV_SOC[PCT],speedKmh,futureMetricColumn
            1000,80,100,ignore-me
        """.trimIndent().toByteArray()

        val (samples, extended) = ObdCsvParser.parse(csv, tripId = 3)

        assertEquals(1, samples.size)
        assertEquals(100.0, samples[0].speedKmh!!, 0.0)
        assertEquals(1000L, samples[0].timestampMillis)
        assertEquals(1, extended.size)
        assertEquals("EV_SOC", extended[0].metricKey)
        assertEquals("PCT", extended[0].unit)
        assertEquals(80.0, extended[0].value, 0.0)
    }
}

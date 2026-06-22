package me.crsib.freecartracker.data.sync

import me.crsib.freecartracker.data.backup.splitCsvLine
import me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Test

class ObdCsvExporterTest {

    @Test
    fun export_headerHasStandardThenSortedPivotedColumns() {
        val samples = listOf(ObdSample(tripId = 1, timestampMillis = 1000, speedKmh = 100.0, rpm = 3000.0))
        val ext = listOf(
            ObdExtendedSample(tripId = 1, timestampMillis = 1000, metricKey = "MOTOR_TEMP", value = 45.0, unit = "C"),
            ObdExtendedSample(tripId = 1, timestampMillis = 1000, metricKey = "EV_SOC", value = 80.0, unit = "PCT"),
        )

        val lines = ObdCsvExporter.export(samples, ext).decodeToString().trim().lines()
        val header = splitCsvLine(lines[0])

        assertEquals("timestampMillis", header.first())
        // standard columns precede pivoted; pivoted keys sorted (EV_SOC before MOTOR_TEMP).
        assertEquals("EV_SOC[PCT]", header[header.size - 2])
        assertEquals("MOTOR_TEMP[C]", header.last())
    }

    @Test
    fun export_rowsKeyedByTimestampUnion_blankForNulls() {
        val samples = listOf(
            ObdSample(tripId = 1, timestampMillis = 1000, speedKmh = 100.0, rpm = 3000.0),
            ObdSample(tripId = 1, timestampMillis = 2000, speedKmh = 0.0),
        )
        // An extended sample at a timestamp with no ObdSample row -> still gets a row.
        val ext = listOf(
            ObdExtendedSample(tripId = 1, timestampMillis = 1000, metricKey = "EV_SOC", value = 80.0, unit = "PCT"),
            ObdExtendedSample(tripId = 1, timestampMillis = 3000, metricKey = "EV_SOC", value = 79.0, unit = "PCT"),
        )

        val lines = ObdCsvExporter.export(samples, ext).decodeToString().trim().lines()
        val header = splitCsvLine(lines[0])
        val rows = lines.drop(1).map { splitCsvLine(it) }
        fun cell(row: List<String>, col: String) = row[header.indexOf(col)]

        // Three distinct timestamps -> three rows, sorted ascending.
        assertEquals(3, rows.size)
        assertEquals(listOf("1000", "2000", "3000"), rows.map { it.first() })

        assertEquals("100", cell(rows[0], "speedKmh"))
        assertEquals("3000", cell(rows[0], "rpm"))
        assertEquals("80", cell(rows[0], "EV_SOC[PCT]"))

        // ts=2000 had a sample but no rpm and no extended -> blanks.
        assertEquals("0", cell(rows[1], "speedKmh"))
        assertEquals("", cell(rows[1], "rpm"))
        assertEquals("", cell(rows[1], "EV_SOC[PCT]"))

        // ts=3000 had only an extended sample -> standard cells blank.
        assertEquals("", cell(rows[2], "speedKmh"))
        assertEquals("79", cell(rows[2], "EV_SOC[PCT]"))
    }

    @Test
    fun parseExtHeader_roundTripsKeyAndUnit() {
        assertEquals("EV_SOC" to "PCT", ObdCsvSchema.parseExtHeader("EV_SOC[PCT]"))
        assertEquals(null, ObdCsvSchema.parseExtHeader("speedKmh"))
    }
}

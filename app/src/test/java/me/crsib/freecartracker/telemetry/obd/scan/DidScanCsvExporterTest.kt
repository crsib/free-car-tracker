package me.crsib.freecartracker.telemetry.obd.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DidScanCsvExporterTest {

    @Test
    fun export_writesHeaderAndOneRowPerResult() {
        val ecu = EcuTarget("BMS?", "7E4")
        val results = listOf(
            DidProbeResult(
                ecu = ecu, did = 0x2002, kind = DidResponseKind.POSITIVE,
                responderHeader = "7EC", payloadHex = "AABB", raw = "7EC 04 62 20 02 AA BB",
            ),
            DidProbeResult(
                ecu = ecu, did = 0x1234, kind = DidResponseKind.NEGATIVE,
                responderHeader = "7EC", negativeCode = 0x31, raw = "7EC 03 7F 22 31",
            ),
        )

        val lines = DidScanCsvExporter.export(results).toString(Charsets.UTF_8).trim().lines()

        assertEquals(3, lines.size) // header + 2 rows
        assertEquals(
            "ecuName,requestHeader,responderHeader,did,kind,negativeCode,multiFrame,payloadHex,raw",
            lines[0],
        )
        assertTrue(lines[1].startsWith("BMS?,7E4,7EC,2002,POSITIVE,,0,AABB,"))
        assertTrue(lines[2].startsWith("BMS?,7E4,7EC,1234,NEGATIVE,31,0,,"))
    }

    @Test
    fun export_flattensNewlinesInRawSoRowsStayIntact() {
        val r = DidProbeResult(
            ecu = EcuTarget("x", "7E0"), did = 0xF190, kind = DidResponseKind.POSITIVE,
            payloadHex = "01", raw = "7E8 10 14 62 F1 90\n7E8 21 01",
        )
        val body = DidScanCsvExporter.export(listOf(r)).toString(Charsets.UTF_8).trim().lines()
        assertEquals(2, body.size) // multi-line raw must not split the CSV row
    }
}

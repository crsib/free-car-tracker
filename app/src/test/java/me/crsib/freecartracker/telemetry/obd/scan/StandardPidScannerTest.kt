package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class SweepFakeObd(val reply: (String) -> String) : RawObd {
    val sent = mutableListOf<String>()
    override suspend fun transceive(command: String): String {
        sent += command
        return reply(command)
    }
}

class StandardPidScannerTest {

    @Test
    fun sweep_capturesSupportedPidsWithPayload() = runTest {
        val fake = SweepFakeObd { cmd ->
            when {
                cmd.startsWith("AT") -> "OK"
                cmd == "01 0D" -> "7E8 03 41 0D 18"          // speed 0x18 = 24 km/h
                cmd == "01 5B" -> "7E8 03 41 5B 6B"          // SoC byte 0x6B
                else -> "NO DATA"
            }
        }
        val finished = StandardPidScanner(fake).sweep(0x00, 0x5B, interProbeDelayMs = 0)
            .toList().filterIsInstance<StdPidScanEvent.Finished>().single()

        val supported = finished.results.filter { it.isSupported }
        assertEquals(2, supported.size)
        assertEquals("18", supported.first { it.pid == 0x0D }.payloadHex)
        assertEquals("6B", supported.first { it.pid == 0x5B }.payloadHex)
        assertTrue("AT SH 7DF" in fake.sent) // addressed the functional broadcast
    }

    @Test
    fun sweep_marksNoDataAndRejectsWrongEcho() = runTest {
        val fake = SweepFakeObd { cmd ->
            when {
                cmd.startsWith("AT") -> "OK"
                cmd == "01 01" -> "NO DATA"
                cmd == "01 02" -> "7E8 03 41 0D 18" // echo for a different PID -> not a valid 0102 reply
                else -> "NO DATA"
            }
        }
        val results = StandardPidScanner(fake).sweep(0x01, 0x02, interProbeDelayMs = 0)
            .toList().filterIsInstance<StdPidScanEvent.Finished>().single().results

        assertEquals(StdPidKind.NO_DATA, results.first { it.pid == 0x01 }.kind)
        assertEquals(StdPidKind.ERROR, results.first { it.pid == 0x02 }.kind)
    }

    @Test
    fun exporter_emitsCandidateDecodingsForMatching() {
        // SoC byte 0x6B = 107 -> 107*100/255 = 41.96 ≈ the real 42% SoC.
        val csv = StdPidCsvExporter.export(
            listOf(StdPidResult(0x5B, StdPidKind.SUPPORTED, "6B", "7E8")),
        ).toString(Charsets.UTF_8).trim().lines()
        assertEquals("pid,kind,responder,payloadHex,A,A*100/255,A-40,AB,AB/10,AB/20,AB*0.05-1600,raw", csv[0])
        assertTrue(csv[1].startsWith("015B,SUPPORTED,7E8,6B,107,41."))
    }
}

package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class InitFakeObd(val reply: (String) -> String) : RawObd {
    val sent = mutableListOf<String>()
    override suspend fun transceive(command: String): String {
        sent += command
        return reply(command)
    }
}

class InitSequenceRecorderTest {

    @Test
    fun record_capturesEachProbeResponseAndSwitchesHeaders() = runTest {
        val fake = InitFakeObd { cmd ->
            when {
                cmd.startsWith("AT") -> "OK"
                cmd == "0100" -> "7E8 06 41 00 BE 3F A8 13"
                cmd == "221201" -> "7E8 07 62 12 01 AA BB CC DD"
                else -> "NO DATA"
            }
        }
        val probes = listOf(
            InitProbe("7DF", "0100"),
            InitProbe("7E0", "221201"),
        )
        val finished = InitSequenceRecorder(fake).record(probes, interProbeDelayMs = 0)
            .toList().filterIsInstance<InitRecordEvent.Finished>().single()

        assertEquals(2, finished.responses.size)
        assertEquals("7E8 06 41 00 BE 3F A8 13", finished.responses[0].raw)
        assertEquals("7E8 07 62 12 01 AA BB CC DD", finished.responses[1].raw)
        // Addressed the identification ECU before 22 1201.
        assertTrue("AT SH 7DF" in fake.sent)
        assertTrue("AT SH 7E0" in fake.sent)
        assertTrue(fake.sent.indexOf("AT SH 7E0") < fake.sent.indexOf("221201"))
    }

    @Test
    fun defaultProbes_coverBitmasksIdentificationAndVin() {
        val cmds = InitSequenceRecorder.DEFAULT_PROBES.map { it.command }
        assertTrue("0100" in cmds)              // supported-PID bitmask
        assertTrue("221201" in cmds)            // Li Auto identification
        assertTrue("0902" in cmds)              // VIN
        // identification DIDs are addressed to 7E0
        assertEquals("7E0", InitSequenceRecorder.DEFAULT_PROBES.first { it.command == "221201" }.header)
    }

    @Test
    fun exporter_writesHeaderCommandRawColumns() {
        val csv = InitMapCsvExporter.export(
            listOf(InitResponse("7E0", "221201", "221201", "7E8 07 62 12 01 AA BB CC DD")),
        ).toString(Charsets.UTF_8).trim().lines()
        assertEquals("header,command,label,raw", csv[0])
        assertTrue(csv[1].startsWith("7E0,221201,221201,7E8 07 62 12 01 AA BB CC DD"))
    }
}

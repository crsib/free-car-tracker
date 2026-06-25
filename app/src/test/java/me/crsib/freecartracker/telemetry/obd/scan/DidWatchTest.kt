package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedRawObd(val reply: (String) -> String) : RawObd {
    val sent = mutableListOf<String>()
    override suspend fun transceive(command: String): String {
        sent += command
        return reply(command)
    }
}

class DidWatchTest {

    @Test
    fun watch_pollsTargetsInRoundsUntilCancelled() = runTest {
        var soc = 0x64 // climbs each probe, like SoC while charging
        val fake = ScriptedRawObd { cmd ->
            when {
                cmd.startsWith("AT") -> "OK"
                cmd == "22 2002" -> "7E8 06 62 20 02 ${"%02X".format(soc++)} 00 00"
                else -> "7E8 03 7F 22 31"
            }
        }
        val target = DidWatchTarget(EcuTarget("BMS?", "7E4"), 0x2002)

        val samples = DidScanner(fake).watch(listOf(target), intervalMs = 0)
            .filterIsInstance<DidWatchEvent.Did>().take(3).toList().map { it.sample }

        assertEquals(3, samples.size)
        assertTrue(samples.all { it.result.kind == DidResponseKind.POSITIVE })
        // Payload's first byte climbs across rounds — the signature you look for when correlating.
        val firstBytes = samples.map { it.result.payloadHex.substring(0, 2).toInt(16) }
        assertEquals(listOf(0x64, 0x65, 0x66), firstBytes)
        assertEquals(listOf(0, 1, 2), samples.map { it.round })
        assertTrue("AT SH 7E4" in fake.sent)
    }

    @Test
    fun watch_reAddressesOnlyWhenEcuChanges() = runTest {
        val fake = ScriptedRawObd { if (it.startsWith("AT")) "OK" else "7E8 03 7F 22 31" }
        val a = DidWatchTarget(EcuTarget("a", "7E0"), 0x0001)
        val b = DidWatchTarget(EcuTarget("b", "7E0"), 0x0002) // same ECU as a
        val c = DidWatchTarget(EcuTarget("c", "7E4"), 0x0003)

        // First round = 3 Did events + 1 RoundDone = 4 events.
        DidScanner(fake).watch(listOf(a, b, c), intervalMs = 0).take(4).toList()

        // AT SH 7E0 (for a), reused for b, then AT SH 7E4 (for c) = 2 header sets, no ref reads.
        assertEquals(2, fake.sent.count { it.startsWith("AT SH ") })
    }

    @Test
    fun watch_readsReferencePidsAgainstFunctionalBroadcast() = runTest {
        val fake = ScriptedRawObd { cmd ->
            when {
                cmd == "01 0D" -> "7E8 03 41 0D 50" // speed 0x50 = 80 km/h
                cmd == "01 0C" -> "7E8 04 41 0C 1A F8" // rpm (0x1AF8)/4 = 1726
                cmd.startsWith("AT") -> "OK"
                else -> "7E8 03 7F 22 31"
            }
        }
        val target = DidWatchTarget(EcuTarget("BMS?", "7E4"), 0x2002)

        val refs = DidScanner(fake).watch(listOf(target), ReferencePids.core, intervalMs = 0)
            .filterIsInstance<DidWatchEvent.Ref>().take(2).toList()

        assertEquals(80.0, refs.first { it.reading.pidKey == "speed" }.reading.value!!, 0.001)
        assertEquals(1726.0, refs.first { it.reading.pidKey == "rpm" }.reading.value!!, 0.001)
        // Standard PIDs are addressed to the functional broadcast so the engine ECU answers.
        assertTrue("AT SH 7DF" in fake.sent)
    }

    @Test
    fun referencePidReader_decodesAndRejects() {
        assertEquals(80.0, ReferencePidReader.read(ReferencePids.SPEED, "7E8 03 41 0D 50")!!, 0.001)
        assertEquals(50.0, ReferencePidReader.read(ReferencePids.COOLANT, "7E8 03 41 05 5A")!!, 0.001) // 0x5A-40
        assertNull("wrong PID echo is rejected", ReferencePidReader.read(ReferencePids.SPEED, "7E8 03 41 0C 50"))
        assertNull("no data -> null", ReferencePidReader.read(ReferencePids.SPEED, "NO DATA"))
    }

    @Test
    fun decoderHint_offersPercentTempAndUint16() {
        val hint = DidDecoders.hint("80AB")
        assertTrue(hint.contains("A=128"))
        assertTrue(hint.contains("50.2%"))
        assertTrue(hint.contains("88°C")) // 128 - 40
        assertTrue(hint.contains("AB=80AB"))
    }

    @Test
    fun decoderHint_emptyForNoPayload() {
        assertEquals("", DidDecoders.hint(""))
    }

    @Test
    fun parseWatchTargets_acceptsCommonFormats() {
        val targets = parseWatchTargets("7E8 2002\n7E0:F190, 18DAF110 1234")
        assertEquals(3, targets.size)
        assertEquals("7E8" to 0x2002, targets[0].ecu.requestHeader to targets[0].did)
        assertEquals("7E0" to 0xF190, targets[1].ecu.requestHeader to targets[1].did)
        assertEquals("18DAF110" to 0x1234, targets[2].ecu.requestHeader to targets[2].did)
    }

    @Test
    fun parseWatchTargets_skipsGarbageAndDedupes() {
        val targets = parseWatchTargets("nonsense\n7E8 2002\n7E8:2002\nZZ 9999\n7E8 GGGG")
        assertEquals(1, targets.size) // the two 7E8/2002 entries dedupe; the rest are invalid
        assertEquals("7E8:2002", targets.single().key)
    }
}

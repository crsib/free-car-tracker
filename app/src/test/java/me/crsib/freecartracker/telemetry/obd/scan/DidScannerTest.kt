package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted ELM327: records every command and returns a canned reply per command. */
private class FakeRawObd(val reply: (String) -> String) : RawObd {
    val sent = mutableListOf<String>()
    override suspend fun transceive(command: String): String {
        sent += command
        return reply(command)
    }
}

class DidScannerTest {

    private val ecu = EcuTarget("BMS?", "7E4")

    @Test
    fun sweep_emitsHitsForPositiveDidsAndFinishes() = runTest {
        // 22 0002 -> positive; everything else negative (NRC 0x31).
        val fake = FakeRawObd { cmd ->
            when {
                cmd.startsWith("AT") -> "OK"
                cmd == "22 0002" -> "7E8 06 62 00 02 AA BB CC"
                cmd.startsWith("22") -> "7E8 03 7F 22 31"
                else -> ""
            }
        }
        val events = DidScanner(fake).scan(
            DidScanConfig(ecus = listOf(ecu), didStart = 0x0000, didEnd = 0x0003, interProbeDelayMs = 0),
        ).toList()

        val hits = events.filterIsInstance<DidScanEvent.Hit>()
        assertEquals(1, hits.size)
        assertEquals(0x0002, hits.first().result.did)
        assertEquals("AABBCC", hits.first().result.payloadHex)
        assertEquals("7E8", hits.first().result.responderHeader)

        val finished = events.filterIsInstance<DidScanEvent.Finished>().single()
        assertEquals(4, finished.probed)
        assertEquals(1, finished.results.size)
        assertFalse(finished.budgetExhausted)

        // Addressed the ECU and probed every DID in range.
        assertTrue("AT SH 7E4" in fake.sent)
        assertTrue("22 0000" in fake.sent)
        assertTrue("22 0003" in fake.sent)
    }

    @Test
    fun maxProbes_stopsEarlyAndReportsExhausted() = runTest {
        val fake = FakeRawObd { if (it.startsWith("AT")) "OK" else "7E8 03 7F 22 31" }
        val finished = DidScanner(fake).scan(
            DidScanConfig(
                ecus = listOf(ecu),
                didStart = 0x0000,
                didEnd = 0x00FF,
                maxProbes = 3,
                interProbeDelayMs = 0,
            ),
        ).toList().filterIsInstance<DidScanEvent.Finished>().single()

        assertEquals(3, finished.probed)
        assertTrue(finished.budgetExhausted)
    }

    @Test
    fun sessionControl_isSentOncePerEcuBeforeProbes() = runTest {
        val fake = FakeRawObd { if (it.startsWith("22") && it != "10 03") "7E8 03 7F 22 31" else "OK" }
        DidScanner(fake).scan(
            DidScanConfig(
                ecus = listOf(ecu),
                didStart = 0x0000,
                didEnd = 0x0001,
                sessionControl = "10 03",
                interProbeDelayMs = 0,
            ),
        ).toList()

        assertEquals(1, fake.sent.count { it == "10 03" })
        // Session control precedes the first DID probe.
        assertTrue(fake.sent.indexOf("10 03") < fake.sent.indexOf("22 0000"))
    }

    @Test
    fun keepNonHits_accumulatesNegatives() = runTest {
        val fake = FakeRawObd { if (it.startsWith("AT")) "OK" else "7E8 03 7F 22 31" }
        val finished = DidScanner(fake).scan(
            DidScanConfig(
                ecus = listOf(ecu),
                didStart = 0x0000,
                didEnd = 0x0002,
                keepNonHits = true,
                interProbeDelayMs = 0,
            ),
        ).toList().filterIsInstance<DidScanEvent.Finished>().single()

        assertEquals(3, finished.results.size)
        assertTrue(finished.results.all { it.kind == DidResponseKind.NEGATIVE })
    }

    @Test
    fun skipPredicate_skipsThoseDidsAndCountsThem() = runTest {
        val fake = FakeRawObd { if (it.startsWith("AT")) "OK" else "7E8 03 7F 22 31" }
        val finished = DidScanner(fake).scan(
            DidScanConfig(
                ecus = listOf(ecu),
                didStart = 0x0000,
                didEnd = 0x0003,
                interProbeDelayMs = 0,
                skip = { _, did -> did == 0x0001 || did == 0x0002 },
            ),
        ).toList().filterIsInstance<DidScanEvent.Finished>().single()

        assertEquals(2, finished.probed) // 0x0000 and 0x0003
        assertEquals(2, finished.skipped)
        assertFalse("22 0001" in fake.sent)
        assertTrue("22 0000" in fake.sent)
    }

    @Test
    fun explicitDidList_probesOnlyThoseInOrder() = runTest {
        val fake = FakeRawObd { if (it.startsWith("AT")) "OK" else "7E8 03 7F 22 31" }
        DidScanner(fake).scan(
            DidScanConfig(ecus = listOf(ecu), dids = listOf(0x4000, 0x2002), interProbeDelayMs = 0),
        ).toList()

        val probes = fake.sent.filter { it.startsWith("22 ") }
        assertEquals(listOf("22 2002", "22 4000"), probes) // sorted ascending
    }

    @Test
    fun classification_sortsHitsDeadAndRetryable() = runTest {
        val fake = FakeRawObd { cmd ->
            when {
                cmd.startsWith("AT") -> "OK"
                cmd == "22 0000" -> "7E8 06 62 00 00 AA BB CC" // hit
                cmd == "22 0001" -> "7E8 03 7F 22 31"          // dead (0x31)
                cmd == "22 0002" -> "7E8 03 7F 22 22"          // retryable (conditionsNotCorrect)
                cmd == "22 0003" -> "NO DATA"                  // retryable (asleep)
                else -> "7E8 03 7F 22 31"
            }
        }
        val c = DidScanner(fake).scan(
            DidScanConfig(ecus = listOf(ecu), didStart = 0x0000, didEnd = 0x0003, interProbeDelayMs = 0),
        ).toList().filterIsInstance<DidScanEvent.EcuClassified>().single().classification

        assertEquals(listOf(0x0000), c.hits)
        assertEquals(listOf(0x0001 to 0x0001), c.deadRanges)
        assertEquals(mapOf(0x0002 to 0x22, 0x0003 to NRC_NO_DATA), c.retryable)
    }

    @Test
    fun discoverEcus_returnsHeadersThatSpeakUds() = runTest {
        // Only 7E4 answers (a negative still proves the ECU is present); 7E0 stays silent.
        var current = ""
        val fake = FakeRawObd { cmd ->
            when {
                cmd.startsWith("AT SH ") -> { current = cmd.removePrefix("AT SH "); "OK" }
                cmd.startsWith("AT") -> "OK"
                else -> if (current == "7E4") "7E8 03 7F 22 31" else "NO DATA"
            }
        }
        val found = DidScanner(fake).discoverEcus(
            headers = listOf(EcuTarget("a", "7E0"), EcuTarget("b", "7E4")),
            probeDids = listOf(0xF190),
            interProbeDelayMs = 0,
        )
        assertEquals(1, found.size)
        assertEquals("7E4", found.single().ecu.requestHeader)
    }
}

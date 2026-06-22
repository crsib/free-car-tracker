package me.crsib.freecartracker.telemetry.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPidsTest {

    @Test
    fun parsesBitmaskSkippingResponseHeader() {
        // "41 00 BE 3E B8 11": mask BE3EB811. BE=10111110 -> PIDs 01,03,04,05,06,07;
        // 3E=00111110 -> 0B,0C,0D,0E,0F. Verify the well-known core PIDs are present.
        val result = SupportedPids.parseBitmask("4100BE3EB811", basePid = 0x00)
        assertTrue("01" in result) // PID 01 (MSB of mask)
        assertTrue("0C" in result) // engine RPM
        assertTrue("0D" in result) // vehicle speed
        assertTrue("05" in result) // coolant temp
        assertFalse("02" in result) // bit clear
        assertFalse("10" in result) // bit clear
    }

    @Test
    fun appliesBaseOffsetForHigherRanges() {
        // mask 80000000 -> only the MSB set -> first PID of the range (base + 1).
        assertEquals(setOf("21"), SupportedPids.parseBitmask("412080000000", basePid = 0x20))
        assertEquals(setOf("41"), SupportedPids.parseBitmask("414080000000", basePid = 0x40))
    }

    @Test
    fun mergesRanges() {
        val merged = SupportedPids.merge(
            listOf(
                "4100BE3EB811" to 0x00,
                "412080000000" to 0x20,
            ),
        )
        assertTrue("0C" in merged)
        assertTrue("21" in merged)
    }

    @Test
    fun malformedOrShortResponseYieldsEmpty() {
        assertTrue(SupportedPids.parseBitmask("", 0x00).isEmpty())
        assertTrue(SupportedPids.parseBitmask("4100BE", 0x00).isEmpty()) // mask too short
    }
}

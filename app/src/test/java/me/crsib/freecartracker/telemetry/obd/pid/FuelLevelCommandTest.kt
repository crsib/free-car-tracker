package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdRawResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies our custom Mode 01 0x2F fuel-level decoder against canned ELM327 responses. */
class FuelLevelCommandTest {

    private val command = FuelLevelCommand()

    private fun decode(raw: String): String =
        command.handler(ObdRawResponse(value = raw, elapsedTime = 0L))

    @Test
    fun decodesFullTank() {
        // 41 2F FF -> data byte 0xFF (255) -> 100.0 %
        assertEquals("100.0", decode("41 2F FF"))
    }

    @Test
    fun decodesQuarterTank() {
        // 41 2F 40 -> data byte 0x40 (64) -> 64 * 100 / 255 = 25.09.. -> "25.1"
        assertEquals("25.1", decode("41 2F 40"))
    }

    @Test
    fun toleratesNoSpaces() {
        assertEquals("0.0", decode("412F00"))
    }

    @Test
    fun takesFirstResponderWhenSeveralEcusAnswer() {
        // Two ECUs answer the broadcast; the library folded both frames into a garbage value. We
        // take the first responder (0x40 -> 25.1 %) instead of concatenating the second (0xFF).
        assertEquals("25.1", decode("41 2F 40\r41 2F FF"))
    }

    @Test
    fun skipsTruncatedResponse() {
        // Echo with no data byte -> non-numeric sentinel so the reading is dropped, not read as 0 %.
        assertEquals("", decode("41 2F"))
    }
}

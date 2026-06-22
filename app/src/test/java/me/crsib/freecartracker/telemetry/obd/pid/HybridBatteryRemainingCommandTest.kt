package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdRawResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies our custom Mode 01 0x5B decoder against canned ELM327 responses. */
class HybridBatteryRemainingCommandTest {

    private val command = HybridBatteryRemainingCommand()

    private fun decode(raw: String): String =
        command.handler(ObdRawResponse(value = raw, elapsedTime = 0L))

    @Test
    fun decodesFullByteAsPercent() {
        // 41 5B FF -> data byte 0xFF (255) -> 100.0 %
        assertEquals("100.0", decode("41 5B FF"))
    }

    @Test
    fun decodesMidScale() {
        // 41 5B 62 -> data byte 0x62 (98) -> 98 * 100 / 255 = 38.43.. -> "38.4"
        assertEquals("38.4", decode("41 5B 62"))
    }

    @Test
    fun toleratesNoSpaces() {
        assertEquals("0.0", decode("415B00"))
    }
}

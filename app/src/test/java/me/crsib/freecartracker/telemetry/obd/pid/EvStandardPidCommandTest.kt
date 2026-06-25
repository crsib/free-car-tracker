package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdRawResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the custom standard-PID decoders for the EV/odometer metrics against canned ELM327
 * responses, including the real frame captured on the car (`41 9A 07 00 5D 73 00 19` → 373.8 V / 2.5 A).
 */
class EvStandardPidCommandTest {

    private fun decode(c: com.github.eltonvs.obd.command.ObdCommand, raw: String): String =
        c.handler(ObdRawResponse(value = raw, elapsedTime = 0L))

    @Test
    fun hvVoltage_decodesRealCarFrame() {
        // 41 9A 07 00 5D 73 00 19 -> voltage bytes 5D 73 = 23923 / 64 = 373.79.. -> "373.8"
        assertEquals("373.8", decode(HvBatteryVoltageCommand(), "41 9A 07 00 5D 73 00 19"))
    }

    @Test
    fun hvCurrent_decodesPositive() {
        // current bytes 00 19 = 25 / 10 = 2.5
        assertEquals("2.5", decode(HvBatteryCurrentCommand(), "41 9A 07 00 5D 73 00 19"))
    }

    @Test
    fun hvCurrent_decodesNegativeAsSigned16() {
        // current bytes FD 0A = 0xFD0A = 64778 -> 64778 - 65536 = -758 / 10 = -75.8 (regen/charge)
        assertEquals("-75.8", decode(HvBatteryCurrentCommand(), "41 9A 07 00 00 00 FD 0A"))
    }

    @Test
    fun hvCurrent_truncatedFrameYieldsBlank() {
        // Only voltage present (6 bytes) -> current decoder has no data -> "" so the reading is skipped.
        assertEquals("", decode(HvBatteryCurrentCommand(), "41 9A 07 00 5D 73"))
    }

    @Test
    fun odometer_decodesRealCarValue() {
        // 41 A6 00 06 37 B4 -> 0x000637B4 = 407476 / 10 = 40747.6 km
        assertEquals("40747.6", decode(OdometerCommand(), "41 A6 00 06 37 B4"))
    }

    @Test
    fun odometer_toleratesNoSpaces() {
        assertEquals("40747.6", decode(OdometerCommand(), "41A6000637B4"))
    }
}

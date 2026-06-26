package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdRawResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the custom standard-PID decoders for the EV/odometer metrics against canned ELM327
 * responses, including the real frames captured on the car: the clean reassembled HV frame
 * (`41 9A 07 00 5D 73 00 19` → 373.8 V / 2.5 A) and the raw on-the-wire framings (multi-ECU and
 * ISO-TP multi-frame, headers off) that previously mis-decoded.
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

    /**
     * The real headers-off, spaces-off multi-frame block as the car/ELM emits it: a bare length
     * line then `0:`/`1:` continuation lines. Reading the raw buffer (the old bug) mis-decoded this
     * as 28 V / 2392 A; the decoder reassembles it back to 373.8 V / 2.5 A.
     */
    @Test
    fun hv_decodesRealWireMultiFrame() {
        val wire = "008\r0:419A07005D7300\r1:195555555555"
        assertEquals("373.8", decode(HvBatteryVoltageCommand(), wire))
        assertEquals("2.5", decode(HvBatteryCurrentCommand(), wire))
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

    @Test
    fun rpm_decodesFirstResponderAcrossMultipleEcus() {
        // Headers off, several ECUs answer 010C, each on its own line. First responder: 1A F8 / 4.
        // 0x1AF8 = 6904 / 4 = 1726.
        assertEquals("1726", decode(RpmCommand(), "410C1AF8\r410C1B20\r410C1AF0"))
    }

    @Test
    fun rpm_decodesSingleResponder() {
        assertEquals("1726", decode(RpmCommand(), "41 0C 1A F8"))
    }

    @Test
    fun controlModuleVoltage_decodesFirstResponderAcrossMultipleEcus() {
        // The five-ECU 0142 reply captured on the car (headers off). First responder 7EB: 0x3423.
        // 13347 / 1000 = 13.347 -> "13.35".
        assertEquals("13.35", decode(ControlModuleVoltageCommand(), "41423423\r414235F0\r41423617\r414235D5\r414235E8"))
    }
}

package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdRawResponse
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.model.Powertrain
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the Mode 22 decoders strip the 3-byte echo (62 + 2 PID bytes) and scale correctly. */
class Mode22CommandTest {

    private val hyundaiEv = Vehicle(name = "EV", powertrain = Powertrain.EV, make = "Hyundai", createdAt = 0L)

    private fun decode(metricKey: String, raw: String): Double {
        val def = PidProfileRegistry.select(hyundaiEv).pids.first { it.key == metricKey }
        return def.command.handler(ObdRawResponse(value = raw, elapsedTime = 0L)).toDouble()
    }

    @Test
    fun evSoc_decodesPercentAfterEchoStrip() {
        // "62 0105 80": echo 62,01,05; data 0x80 (128) -> 128*100/255 = 50.196..%
        assertEquals(50.196, decode("EV_SOC", "62 01 05 80"), 0.01)
    }

    @Test
    fun motorTemp_decodesWithOffset() {
        // "62 0101 5A": data 0x5A (90) -> 90 - 40 = 50 °C
        assertEquals(50.0, decode("MOTOR_TEMP", "62 01 01 5A"), 0.001)
    }

    @Test
    fun toleratesNoSpaces() {
        assertEquals(0.0, decode("EV_SOC", "62010500"), 0.001)
    }
}

package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdRawResponse
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.model.Powertrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PidProfileRegistryTest {

    private fun vehicle(powertrain: Powertrain, make: String? = null) =
        Vehicle(name = "Test", powertrain = powertrain, make = make, createdAt = 0L)

    @Test
    fun ice_getsStandardBaselineOnly() {
        val selected = PidProfileRegistry.select(vehicle(Powertrain.ICE))
        assertEquals(StandardPidProfile.key, selected.key)
        assertEquals(StandardPidProfile.pids, selected.pids)
    }

    @Test
    fun ev_withoutMatchingProfile_fallsBackToBaseline() {
        // No profile authored for this make, so even an EV gets only the baseline.
        val selected = PidProfileRegistry.select(vehicle(Powertrain.EV, make = "Tesla"))
        assertEquals(StandardPidProfile.key, selected.key)
        assertEquals(StandardPidProfile.pids, selected.pids)
    }

    @Test
    fun ev_withMatchingMake_addsMode22Metrics() {
        val selected = PidProfileRegistry.select(vehicle(Powertrain.EV, make = "Hyundai"))
        assertEquals("HYUNDAI_KIA", selected.key)
        val keys = selected.pids.map { it.key }
        assertTrue("EV_SOC" in keys)
        assertTrue("MOTOR_TEMP" in keys)
        // Baseline is still included.
        assertTrue("speedKmh" in keys)
    }

    @Test
    fun iceWithEvMake_doesNotGetEvProfile() {
        // An ICE car of an EV-making brand must not get plug-in EV PIDs.
        val selected = PidProfileRegistry.select(vehicle(Powertrain.ICE, make = "Hyundai"))
        assertEquals(StandardPidProfile.key, selected.key)
        assertTrue(selected.pids.none { it.key == "EV_SOC" })
    }

    @Test
    fun baselineIncludesCoreAndHybridBatteryPids() {
        val keys = StandardPidProfile.pids.map { it.key }.toSet()
        assertTrue(keys.containsAll(setOf("speedKmh", "rpm", "throttlePct", "engineLoadPct")))
        assertTrue("hybridBatteryLifePct" in keys)
    }

    @Test
    fun baselineIncludesHvPackOdometerAndTimingPids() {
        val byKey = StandardPidProfile.pids.associateBy { it.key }
        assertTrue(byKey.keys.containsAll(setOf("hvBatteryVoltage", "odometerKm", "timingAdvanceDeg")))
        // The high-range PIDs (0x9A, 0xA6) must bypass the supported-PID discovery gate.
        assertTrue(byKey.getValue("hvBatteryVoltage").skipDiscoveryGate)
        assertTrue(byKey.getValue("odometerKm").skipDiscoveryGate)
        // Timing advance (0x0E) is in a discovered range, so it stays gated.
        assertEquals(false, byKey.getValue("timingAdvanceDeg").skipDiscoveryGate)
    }

    @Test
    fun hvCurrentIsACompanionOfVoltageSharingTheOneFrame() {
        // Voltage and current both live in PID 0x9A; current rides along as a companion so the
        // pack frame is read once and the two values stay time-aligned (power = V×I).
        val voltage = StandardPidProfile.pids.first { it.key == "hvBatteryVoltage" }
        assertTrue(StandardPidProfile.pids.none { it.key == "hvBatteryCurrent" })
        val current = voltage.companions.single()
        assertEquals("hvBatteryCurrent", current.key)
        // 41 9A 07 00 5D 73 00 19 -> current bytes 00 19 = 25 / 10 = 2.5 A
        val decoded = current.decode(ObdRawResponse(value = "41 9A 07 00 5D 73 00 19", elapsedTime = 0L))
        assertEquals(2.5, decoded!!, 1e-6)
    }
}

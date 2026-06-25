package me.crsib.freecartracker.telemetry.obd.pid

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
        assertTrue(byKey.keys.containsAll(setOf("hvBatteryVoltage", "hvBatteryCurrent", "odometerKm", "timingAdvanceDeg")))
        // The high-range PIDs (0x9A, 0xA6) must bypass the supported-PID discovery gate.
        assertTrue(byKey.getValue("hvBatteryVoltage").skipDiscoveryGate)
        assertTrue(byKey.getValue("hvBatteryCurrent").skipDiscoveryGate)
        assertTrue(byKey.getValue("odometerKm").skipDiscoveryGate)
        // Timing advance (0x0E) is in a discovered range, so it stays gated.
        assertEquals(false, byKey.getValue("timingAdvanceDeg").skipDiscoveryGate)
    }
}

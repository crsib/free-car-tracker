package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.command.bytesToInt
import com.github.eltonvs.obd.command.control.ModuleVoltageCommand
import com.github.eltonvs.obd.command.control.TimingAdvanceCommand
import com.github.eltonvs.obd.command.engine.LoadCommand
import com.github.eltonvs.obd.command.engine.MassAirFlowCommand
import com.github.eltonvs.obd.command.engine.RPMCommand
import com.github.eltonvs.obd.command.engine.RuntimeCommand
import com.github.eltonvs.obd.command.engine.SpeedCommand
import com.github.eltonvs.obd.command.engine.ThrottlePositionCommand
import com.github.eltonvs.obd.command.fuel.FuelConsumptionRateCommand
import com.github.eltonvs.obd.command.fuel.FuelLevelCommand
import com.github.eltonvs.obd.command.pressure.IntakeManifoldPressureCommand
import com.github.eltonvs.obd.command.temperature.AirIntakeTemperatureCommand
import com.github.eltonvs.obd.command.temperature.AmbientAirTemperatureCommand
import com.github.eltonvs.obd.command.temperature.EngineCoolantTemperatureCommand
import me.crsib.freecartracker.data.entity.Vehicle

/** A set of PID definitions applicable to some vehicles. */
interface PidProfile {
    val key: String
    fun appliesTo(vehicle: Vehicle): Boolean
    val pids: List<PidDefinition>
}

/**
 * Mode 01 PID 0x5B — hybrid/EV battery pack remaining life (%). Not provided by the library,
 * so defined here. With headers/spaces off the response is `41 5B A`; the library puts the
 * echo bytes at indices 0,1 and the data byte A at index 2 (value = A * 100 / 255).
 */
class HybridBatteryRemainingCommand : ObdCommand() {
    override val tag = "HYBRID_BATTERY_REMAINING"
    override val name = "Hybrid Battery Remaining Life"
    override val mode = "01"
    override val pid = "5B"
    override val defaultUnit = "%"
    override val handler: (ObdRawResponse) -> String = { r ->
        val bytes = r.bufferedValue
        // Truncated / NO DATA response → emit a non-numeric sentinel so the reading is skipped
        // (ObdSession.query nulls it) instead of fabricating a real-looking 0 %.
        if (bytes.size >= 3) "%.1f".format(bytes[2] * 100.0 / 255.0) else ""
    }
}

/**
 * Mode 01 PID 0x9A — Hybrid/EV System Data, Battery (SAE J1979-2). One response carries both
 * pack voltage and current; we read it twice (one command each) to fit the one-value-per-column
 * model. Response (headers/spaces off) is `41 9A b0 b1 b2 b3 b4 b5`: b0 is a support/status byte,
 * voltage = (b2<<8 | b3) / 64 V, current = signed16(b4<<8 | b5) / 10 A (negative = regen/charge).
 * Validated on the real car: 373.8 V / 2.5 A parked. Only ECU 7EB answers, so no responder clash.
 */
class HvBatteryVoltageCommand : ObdCommand() {
    override val tag = "HV_BATTERY_VOLTAGE"
    override val name = "HV Battery Voltage"
    override val mode = "01"
    override val pid = "9A"
    override val defaultUnit = "V"
    override val handler: (ObdRawResponse) -> String = { r ->
        val b = r.bufferedValue
        if (b.size >= 6) "%.1f".format(bytesToInt(b, start = 4, bytesToProcess = 2) / 64.0) else ""
    }
}

/** Mode 01 PID 0x9A current half — see [HvBatteryVoltageCommand]. signed16 / 10 (negative = regen). */
class HvBatteryCurrentCommand : ObdCommand() {
    override val tag = "HV_BATTERY_CURRENT"
    override val name = "HV Battery Current"
    override val mode = "01"
    override val pid = "9A"
    override val defaultUnit = "A"
    override val handler: (ObdRawResponse) -> String = { r ->
        val b = r.bufferedValue
        if (b.size >= 8) {
            val raw = bytesToInt(b, start = 6, bytesToProcess = 2)
            val signed = if (raw >= 0x8000) raw - 0x10000 else raw
            "%.1f".format(signed / 10.0)
        } else {
            ""
        }
    }
}

/**
 * Mode 01 PID 0xA6 — Odometer (SAE). Response `41 A6 A B C D`; value = (A<<24|B<<16|C<<8|D) / 10 km.
 * Validated on the real car: 40 747.6 km.
 */
class OdometerCommand : ObdCommand() {
    override val tag = "ODOMETER"
    override val name = "Odometer"
    override val mode = "01"
    override val pid = "A6"
    override val defaultUnit = "km"
    override val handler: (ObdRawResponse) -> String = { r ->
        val b = r.bufferedValue
        if (b.size >= 6) "%.1f".format(bytesToInt(b, start = 2, bytesToProcess = 4) / 10.0) else ""
    }
}

/** Always-applied baseline of standard OBD-II (Mode 01) metrics. */
object StandardPidProfile : PidProfile {
    override val key = "GENERIC"
    override fun appliesTo(vehicle: Vehicle): Boolean = true

    override val pids: List<PidDefinition> = listOf(
        // Core driving dynamics (FAST)
        PidDefinition("speedKmh", SpeedCommand(), "km/h", MetricGroup.CORE, PollPriority.FAST, Storage.Wide("speedKmh")),
        PidDefinition("rpm", RPMCommand(), "rpm", MetricGroup.CORE, PollPriority.FAST, Storage.Wide("rpm")),
        PidDefinition("throttlePct", ThrottlePositionCommand(), "%", MetricGroup.CORE, PollPriority.FAST, Storage.Wide("throttlePct")),
        PidDefinition("engineLoadPct", LoadCommand(), "%", MetricGroup.CORE, PollPriority.FAST, Storage.Wide("engineLoadPct")),
        PidDefinition("runTimeSec", RuntimeCommand(), "s", MetricGroup.CORE, PollPriority.SLOW, Storage.Wide("runTimeSec")),
        // Engine health (SLOW)
        PidDefinition("coolantTempC", EngineCoolantTemperatureCommand(), "°C", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("coolantTempC")),
        PidDefinition("timingAdvanceDeg", TimingAdvanceCommand(), "°", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("timingAdvanceDeg")),
        PidDefinition("intakeAirTempC", AirIntakeTemperatureCommand(), "°C", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("intakeAirTempC")),
        PidDefinition("ambientAirTempC", AmbientAirTemperatureCommand(), "°C", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("ambientAirTempC")),
        PidDefinition("mafGramsPerSec", MassAirFlowCommand(), "g/s", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("mafGramsPerSec")),
        PidDefinition("manifoldPressureKpa", IntakeManifoldPressureCommand(), "kPa", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("manifoldPressureKpa")),
        PidDefinition("controlModuleVoltage", ModuleVoltageCommand(), "V", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("controlModuleVoltage")),
        // Fuel & energy (SLOW)
        PidDefinition("fuelLevelPct", FuelLevelCommand(), "%", MetricGroup.FUEL_ENERGY, PollPriority.SLOW, Storage.Wide("fuelLevelPct")),
        PidDefinition("fuelRateLph", FuelConsumptionRateCommand(), "L/h", MetricGroup.FUEL_ENERGY, PollPriority.SLOW, Storage.Wide("fuelRateLph")),
        // Odometer (SLOW). PID 0xA6 is above the discovered ranges, so bypass the discovery gate.
        PidDefinition("odometerKm", OdometerCommand(), "km", MetricGroup.CORE, PollPriority.SLOW, Storage.Wide("odometerKm"), skipDiscoveryGate = true),
        // Hybrid/EV standard PIDs (SLOW). 0x9A (HV V/A) is also above the discovered ranges.
        PidDefinition("hybridBatteryLifePct", HybridBatteryRemainingCommand(), "%", MetricGroup.EV_HYBRID, PollPriority.SLOW, Storage.Wide("hybridBatteryLifePct")),
        PidDefinition("hvBatteryVoltage", HvBatteryVoltageCommand(), "V", MetricGroup.EV_HYBRID, PollPriority.SLOW, Storage.Wide("hvBatteryVoltage"), skipDiscoveryGate = true),
        PidDefinition("hvBatteryCurrent", HvBatteryCurrentCommand(), "A", MetricGroup.EV_HYBRID, PollPriority.SLOW, Storage.Wide("hvBatteryCurrent"), skipDiscoveryGate = true),
    )
}

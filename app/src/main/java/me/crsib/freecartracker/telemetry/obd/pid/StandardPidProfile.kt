package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.command.control.TimingAdvanceCommand
import com.github.eltonvs.obd.command.engine.LoadCommand
import com.github.eltonvs.obd.command.engine.MassAirFlowCommand
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
 * Mode 01 PID 0x0C — Engine RPM. The library's [com.github.eltonvs.obd.command.engine.RPMCommand]
 * folds [com.github.eltonvs.obd.command.bytesToInt] across the whole buffer, so when several ECUs
 * answer the broadcast (as on the Lixiang) it sums every responder's bytes into a ~1e18 garbage
 * value. We decode the first responder's two data bytes instead: rpm = (A<<8 | B) / 4.
 */
class RpmCommand : ObdCommand() {
    override val tag = "ENGINE_RPM"
    override val name = "Engine RPM"
    override val mode = "01"
    override val pid = "0C"
    override val defaultUnit = "rpm"
    override val handler: (ObdRawResponse) -> String = { r ->
        val d = Mode01ResponseDecoder.dataBytes(r.value, pid)
        if (d != null && d.size >= 2) ((d[0] shl 8 or d[1]) / 4).toString() else ""
    }
}

/**
 * Mode 01 PID 0x42 — Control module power supply. Same multi-ECU problem as [RpmCommand]: five
 * ECUs answer on the Lixiang, so the library's whole-buffer fold yields ~1e15 V. We decode the
 * first responder's two data bytes: volts = (A<<8 | B) / 1000.
 */
class ControlModuleVoltageCommand : ObdCommand() {
    override val tag = "CONTROL_MODULE_VOLTAGE"
    override val name = "Control Module Power Supply"
    override val mode = "01"
    override val pid = "42"
    override val defaultUnit = "V"
    override val handler: (ObdRawResponse) -> String = { r ->
        val d = Mode01ResponseDecoder.dataBytes(r.value, pid)
        if (d != null && d.size >= 2) "%.2f".format((d[0] shl 8 or d[1]) / 1000.0) else ""
    }
}

/**
 * Mode 01 PID 0x5B — hybrid/EV battery pack remaining life (%). Not provided by the library.
 * Response data is one byte A; value = A * 100 / 255. A truncated / NO DATA response yields a
 * non-numeric sentinel so the reading is skipped ([ObdSession.query] nulls it) instead of
 * fabricating a real-looking 0 %.
 */
class HybridBatteryRemainingCommand : ObdCommand() {
    override val tag = "HYBRID_BATTERY_REMAINING"
    override val name = "Hybrid Battery Remaining Life"
    override val mode = "01"
    override val pid = "5B"
    override val defaultUnit = "%"
    override val handler: (ObdRawResponse) -> String = { r ->
        val d = Mode01ResponseDecoder.dataBytes(r.value, pid)
        if (d != null && d.isNotEmpty()) "%.1f".format(d[0] * 100.0 / 255.0) else ""
    }
}

/**
 * Mode 01 PID 0x9A — Hybrid/EV System Data, Battery (SAE J1979-2). One response carries both pack
 * voltage and current; we read it twice (one command each) to fit the one-value-per-column model.
 * The data bytes (after the `41 9A` echo) are `b0 b1 b2 b3 b4 b5`: b0 is a support/status byte,
 * voltage = (b2<<8 | b3) / 64 V, current = signed16(b4<<8 | b5) / 10 A (negative = regen/charge).
 * Validated on the real car: 373.8 V / 2.5 A parked.
 *
 * On this car 0x9A is an ISO-TP multi-frame reply; with headers off ELM renders it as a length
 * line plus `0:`/`1:` continuation lines, which [Mode01ResponseDecoder] reassembles. Reading the
 * raw [com.github.eltonvs.obd.command.ObdRawResponse.bufferedValue] instead (as before) left the
 * length/index nibbles in the stream and mis-decoded it as 28 V / 2392 A.
 */
class HvBatteryVoltageCommand : ObdCommand() {
    override val tag = "HV_BATTERY_VOLTAGE"
    override val name = "HV Battery Voltage"
    override val mode = "01"
    override val pid = "9A"
    override val defaultUnit = "V"
    override val handler: (ObdRawResponse) -> String = { r ->
        val d = Mode01ResponseDecoder.dataBytes(r.value, pid)
        if (d != null && d.size >= 4) "%.1f".format((d[2] shl 8 or d[3]) / 64.0) else ""
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
        val d = Mode01ResponseDecoder.dataBytes(r.value, pid)
        if (d != null && d.size >= 6) {
            val raw = d[4] shl 8 or d[5]
            val signed = if (raw >= 0x8000) raw - 0x10000 else raw
            "%.1f".format(signed / 10.0)
        } else {
            ""
        }
    }
}

/**
 * Mode 01 PID 0xA6 — Odometer (SAE). Data bytes (after `41 A6`) are A B C D; value =
 * (A<<24|B<<16|C<<8|D) / 10 km. Validated on the real car: 40 747.6 km.
 */
class OdometerCommand : ObdCommand() {
    override val tag = "ODOMETER"
    override val name = "Odometer"
    override val mode = "01"
    override val pid = "A6"
    override val defaultUnit = "km"
    override val handler: (ObdRawResponse) -> String = { r ->
        val d = Mode01ResponseDecoder.dataBytes(r.value, pid)
        if (d != null && d.size >= 4) {
            val value = (d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()
            "%.1f".format(value / 10.0)
        } else {
            ""
        }
    }
}

/** Always-applied baseline of standard OBD-II (Mode 01) metrics. */
object StandardPidProfile : PidProfile {
    override val key = "GENERIC"
    override fun appliesTo(vehicle: Vehicle): Boolean = true

    override val pids: List<PidDefinition> = listOf(
        // Core driving dynamics (FAST)
        PidDefinition("speedKmh", SpeedCommand(), "km/h", MetricGroup.CORE, PollPriority.FAST, Storage.Wide("speedKmh")),
        PidDefinition("rpm", RpmCommand(), "rpm", MetricGroup.CORE, PollPriority.FAST, Storage.Wide("rpm")),
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
        PidDefinition("controlModuleVoltage", ControlModuleVoltageCommand(), "V", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("controlModuleVoltage")),
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

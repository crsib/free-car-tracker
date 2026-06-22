package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.command.control.ModuleVoltageCommand
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
        PidDefinition("intakeAirTempC", AirIntakeTemperatureCommand(), "°C", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("intakeAirTempC")),
        PidDefinition("ambientAirTempC", AmbientAirTemperatureCommand(), "°C", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("ambientAirTempC")),
        PidDefinition("mafGramsPerSec", MassAirFlowCommand(), "g/s", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("mafGramsPerSec")),
        PidDefinition("manifoldPressureKpa", IntakeManifoldPressureCommand(), "kPa", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("manifoldPressureKpa")),
        PidDefinition("controlModuleVoltage", ModuleVoltageCommand(), "V", MetricGroup.ENGINE_HEALTH, PollPriority.SLOW, Storage.Wide("controlModuleVoltage")),
        // Fuel & energy (SLOW)
        PidDefinition("fuelLevelPct", FuelLevelCommand(), "%", MetricGroup.FUEL_ENERGY, PollPriority.SLOW, Storage.Wide("fuelLevelPct")),
        PidDefinition("fuelRateLph", FuelConsumptionRateCommand(), "L/h", MetricGroup.FUEL_ENERGY, PollPriority.SLOW, Storage.Wide("fuelRateLph")),
        // Hybrid/EV standard PID (SLOW)
        PidDefinition("hybridBatteryLifePct", HybridBatteryRemainingCommand(), "%", MetricGroup.EV_HYBRID, PollPriority.SLOW, Storage.Wide("hybridBatteryLifePct")),
    )
}

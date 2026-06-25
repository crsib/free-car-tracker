package me.crsib.freecartracker.telemetry.service

import me.crsib.freecartracker.domain.model.ObdTransportKind

/** Runtime status of the recorder, surfaced to the UI. */
enum class RecordingState {
    IDLE,
    CONNECTING,
    RECORDING,
    RECONNECTING,
    ERROR,
}

/**
 * Latest live values for the gauge dashboard. Updated at the FAST poll rate. All telemetry
 * fields are nullable (a metric may be unsupported or not yet read).
 */
data class LiveTelemetry(
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val throttlePct: Double? = null,
    val engineLoadPct: Double? = null,
    val coolantTempC: Double? = null,
    val controlModuleVoltage: Double? = null,
    val fuelLevelPct: Double? = null,
    val hybridBatteryLifePct: Double? = null,
    val hvBatteryVoltage: Double? = null,
    val hvBatteryCurrent: Double? = null,
    /** Derived HV battery power (kW) = voltage × current / 1000; negative = regen/charge. */
    val hvBatteryPowerKw: Double? = null,
    val odometerKm: Double? = null,
    /** Manufacturer Mode 22 metrics (EV SoC, motor temp, …) keyed by metricKey. */
    val extended: Map<String, Double> = emptyMap(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsSpeedKmh: Double? = null,
    val elapsedSec: Long = 0,
    val distanceKm: Double = 0.0,
    val obdConnected: Boolean = false,
    val gpsFix: Boolean = false,
    val transport: ObdTransportKind = ObdTransportKind.NONE,
)

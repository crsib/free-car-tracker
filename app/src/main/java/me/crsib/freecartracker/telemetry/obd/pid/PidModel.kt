package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdCommand

/** Which dashboard/storage group a metric belongs to. */
enum class MetricGroup { CORE, ENGINE_HEALTH, FUEL_ENERGY, EV_HYBRID }

/** Polling tier. FAST PIDs are read every cycle; SLOW ones are round-robined. */
enum class PollPriority { FAST, SLOW }

/** Where a decoded metric is persisted. */
sealed interface Storage {
    /** A named nullable column on `obd_samples` (the [column] must match the entity field). */
    data class Wide(val column: String) : Storage

    /** A row in `obd_extended_samples`, keyed by the definition's `key` as metricKey. */
    data object Extended : Storage
}

/**
 * One pollable metric: a library [command] plus how we classify, schedule and store it.
 * [key] is the stable metric id. For [Storage.Wide] it must equal the `obd_samples` column
 * name; for [Storage.Extended] it is the `obd_extended_samples.metricKey`.
 */
class PidDefinition(
    val key: String,
    val command: ObdCommand,
    val unit: String,
    val group: MetricGroup,
    val priority: PollPriority,
    val storage: Storage,
    /**
     * When true this Mode 01 PID is polled even if it's absent from the supported-PID bitmask.
     * Used for high-range PIDs (≥ 0x80) the AvailablePIDs discovery doesn't probe, and where the
     * bitmask read is unreliable anyway (multiple ECUs answer the broadcast). [ObdSession.query]
     * returns null on NO DATA, so polling an unsupported PID is harmless.
     */
    val skipDiscoveryGate: Boolean = false,
)

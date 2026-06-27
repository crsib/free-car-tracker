package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse

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
     * returns no reading on NO DATA, so polling an unsupported PID is harmless.
     */
    val skipDiscoveryGate: Boolean = false,
    /**
     * Extra metrics decoded from the SAME command response as this PID. One OBD PID can pack
     * several values into a single frame (e.g. PID 0x9A returns HV pack voltage AND current).
     * Pulling them from one query keeps the values time-aligned — so derived quantities like
     * power (V×I) are taken from a single instant — and halves bus traffic versus polling the
     * same PID once per value. See [ObdSession.query].
     */
    val companions: List<CompanionMetric> = emptyList(),
)

/**
 * A metric decoded from its parent [PidDefinition]'s command response, sharing that one query
 * (and therefore its timestamp). [decode] receives the raw response and returns the value, or
 * null when the frame carries no value for this metric (skipped, like a NO DATA primary read).
 */
class CompanionMetric(
    val key: String,
    val unit: String,
    val storage: Storage,
    val decode: (ObdRawResponse) -> Double?,
)

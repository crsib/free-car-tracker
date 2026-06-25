package me.crsib.freecartracker.telemetry.obd.scan

/**
 * Reverse-engineering support for manufacturer UDS data. There is no published PID/DID map for
 * Li Auto (Lixiang); standard OBD-II mode 01 only reaches the range-extender ICE. The interesting
 * EV signals (pack SoC/voltage/current, cell temps, motor power) live behind manufacturer
 * UDS service 0x22 "ReadDataByIdentifier" DIDs that are vehicle/ECU-specific and undocumented.
 *
 * This package sweeps `22 xx xx` across candidate ECUs and classifies each reply so the responding
 * DIDs can be captured, exported, and later turned into a [me.crsib.freecartracker.telemetry.obd
 * .pid.PidProfile]. It is a discovery tool, not part of the trip-recording poll loop.
 */

/** A target ECU to probe: a human label plus its UDS request CAN id (hex, e.g. "7E0" or "18DA10F1"). */
data class EcuTarget(
    val name: String,
    /** Request arbitration id sent via `AT SH`. 11-bit (3 hex) or 29-bit (8 hex). */
    val requestHeader: String,
    val note: String = "",
)

/** How an ECU answered a single `22 xx xx` probe. */
enum class DidResponseKind {
    /** `62 <did> <data…>` — the DID exists and returned data. This is a hit. */
    POSITIVE,

    /** `7F 22 <nrc>` — service understood, request rejected (e.g. NRC 0x31 = requestOutOfRange). */
    NEGATIVE,

    /** Adapter reported `NO DATA` — no ECU answered within the timeout. */
    NO_DATA,

    /** Adapter/bus error (`?`, `BUS INIT`, `CAN ERROR`, `STOPPED`, …) — probe inconclusive. */
    ERROR,

    /** Empty / unparseable reply. */
    EMPTY,
}

/** Result of probing one DID on one ECU. */
data class DidProbeResult(
    val ecu: EcuTarget,
    /** The requested data identifier, 0x0000..0xFFFF. */
    val did: Int,
    val kind: DidResponseKind,
    /** Arbitration id that actually replied (from `AT H1`), or null when headers were off/absent. */
    val responderHeader: String? = null,
    /** Negative response code when [kind] is [DidResponseKind.NEGATIVE]. */
    val negativeCode: Int? = null,
    /** Positive-response data bytes with the `62 <did>` echo stripped, as contiguous hex (no spaces). */
    val payloadHex: String = "",
    /** True when the positive reply spanned multiple ISO-TP frames (first-frame + consecutive). */
    val multiFrame: Boolean = false,
    /** The raw adapter text, kept verbatim for offline analysis. */
    val raw: String = "",
) {
    val didHex: String get() = "%04X".format(did)
    val isHit: Boolean get() = kind == DidResponseKind.POSITIVE

    /**
     * Parser output before the requested DID/ECU context is attached. Keeps [DidResponseParser] a
     * pure function of (requestedDid, raw); the scanner promotes it via [toResult].
     */
    data class Partial(
        val kind: DidResponseKind,
        val responderHeader: String? = null,
        val negativeCode: Int? = null,
        val payloadHex: String = "",
        val multiFrame: Boolean = false,
        val raw: String = "",
    )
}

/**
 * The vehicle/session conditions a scan ran under. Skip-sets are scoped to this, because a DID that
 * answers `0x31` (not supported) *here* may answer with data in another state — `0x31` parked-default
 * isn't proof of `0x31` while driving or in an extended session. [state] is a user-chosen label.
 */
data class ScanContext(val state: String, val session: String) {
    val key: String get() = "$state|$session"

    companion object {
        const val SESSION_DEFAULT = "DEFAULT"
        const val SESSION_EXTENDED = "EXTENDED"
    }
}

/** Common [ScanContext.state] labels. Free-form; these are just the ones the UI offers. */
object ScanStates {
    const val PARKED = "PARKED"
    const val DRIVING = "DRIVING"
    const val CHARGING = "CHARGING"
    val all = listOf(PARKED, DRIVING, CHARGING)
}

/** Sentinel NRC for "no reply" (ECU asleep/unreachable) in [EcuClassification.retryable]. */
const val NRC_NO_DATA = -1

/**
 * What one ECU's DIDs classified as during a sweep — the data the skip-memory is built from:
 *  - [hits]: returned data (`62 …`).
 *  - [deadRanges]: confirmed `0x31` (not supported) — safe to skip on a re-scan in the same context.
 *  - [retryable]: did → NRC for `conditionsNotCorrect`/`securityAccessDenied`/session NRCs and
 *    [NRC_NO_DATA]; these "maybe later" DIDs are the ones to re-probe in a new state.
 * `ERROR`/`EMPTY` replies are inconclusive and deliberately omitted (so they're re-probed).
 */
data class EcuClassification(
    val ecu: EcuTarget,
    val hits: List<Int>,
    val deadRanges: List<Pair<Int, Int>>,
    val retryable: Map<Int, Int>,
)

/** One (ECU, DID) to poll repeatedly in live-watch mode. */
data class DidWatchTarget(val ecu: EcuTarget, val did: Int) {
    val didHex: String get() = "%04X".format(did)

    /** Stable id for keying a UI row: "<requestHeader>:<didHex>". */
    val key: String get() = "${ecu.requestHeader.uppercase()}:$didHex"
}

/** One live reading of a watched DID; [round] increments each full pass over the target list. */
data class DidWatchSample(val target: DidWatchTarget, val result: DidProbeResult, val round: Int)

/** One live reading of a standard reference PID (speed, RPM…); [value] is null when unavailable. */
data class RefReading(val pidKey: String, val label: String, val unit: String, val value: Double?)

/** Streamed from [DidScanner.watch]: a DID reading, a reference-PID reading, or end-of-round. */
sealed interface DidWatchEvent {
    data class Did(val sample: DidWatchSample) : DidWatchEvent
    data class Ref(val reading: RefReading, val round: Int) : DidWatchEvent
    /** Marks one full pass complete — the cue to log a synchronized time-series row. */
    data class RoundDone(val round: Int) : DidWatchEvent
}

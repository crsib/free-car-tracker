package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How a scan is bounded and paced. Defaults err toward a slow, polite sweep. */
data class DidScanConfig(
    val ecus: List<EcuTarget>,
    /** Inclusive DID range to sweep on each ECU. */
    val didStart: Int = 0x0000,
    val didEnd: Int = 0xFFFF,
    /** Delay between probes (ms). Too fast floods slow ECUs and yields false NO_DATA. */
    val interProbeDelayMs: Long = 60L,
    /** Hard cap on total probes across all ECUs — a runaway backstop. */
    val maxProbes: Int = 8_192,
    /** Keep negative/no-data results too (verbose). When false, only positives are accumulated. */
    val keepNonHits: Boolean = false,
    /**
     * UDS session to enter once per ECU before sweeping, e.g. "10 03" (extended diagnostic session).
     * Many DIDs are readable in the default session; some require extended. Null = stay in default.
     */
    val sessionControl: String? = null,
    /**
     * Skip predicate `(ecuRequestHeader, did) -> shouldSkip`. Used to skip DIDs already known dead
     * in this context (the persistent skip-set), so a re-scan finishes in a fraction of the time.
     */
    val skip: (String, Int) -> Boolean = { _, _ -> false },
    /**
     * Explicit DIDs to probe per ECU instead of [didStart]..[didEnd]. Set this for a targeted
     * "re-probe the interesting set" pass in a new state. Probed in ascending order.
     */
    val dids: List<Int>? = null,
)

/** Streamed progress from a [DidScanner.scan]. */
sealed interface DidScanEvent {
    data class Started(val totalEcus: Int, val didCount: Int) : DidScanEvent
    data class EcuStarted(val ecu: EcuTarget) : DidScanEvent
    /** A DID that returned data — the payload worth keeping. */
    data class Hit(val result: DidProbeResult) : DidScanEvent
    /** Periodic heartbeat so a UI can show progress without an event per DID. */
    data class Progress(
        val ecu: EcuTarget,
        val did: Int,
        val probed: Int,
        val hits: Int,
        /** DIDs skipped via the skip-set so far. `probed + skipped` is DID-space covered. */
        val skipped: Int = 0,
    ) : DidScanEvent
    /** Per-ECU classification (hits/dead/retryable), emitted as each ECU finishes — feeds skip-memory. */
    data class EcuClassified(val classification: EcuClassification) : DidScanEvent
    data class EcuFinished(val ecu: EcuTarget, val hits: Int) : DidScanEvent
    data class Finished(
        val results: List<DidProbeResult>,
        val probed: Int,
        val budgetExhausted: Boolean,
        val skipped: Int = 0,
        val classifications: List<EcuClassification> = emptyList(),
    ) : DidScanEvent
}

/**
 * Sweeps UDS service 0x22 across candidate ECUs to discover which data identifiers (DIDs) return
 * data. Emits a cold [Flow] of [DidScanEvent]s; collect it to drive a UI or accumulate results.
 *
 * Run on an adapter that has completed [Elm327Initializer][me.crsib.freecartracker.telemetry.obd
 * .Elm327Initializer] setup. The scanner reconfigures the adapter for parseable, attributable replies
 * (spaces on, headers on) and addresses each ECU with `AT SH`. It is exclusive: nothing else may use
 * the adapter while a scan runs.
 */
class DidScanner(private val raw: RawObd) {

    fun scan(config: DidScanConfig): Flow<DidScanEvent> = flow {
        val targets: Iterable<Int> = config.dids?.toSortedSet() ?: config.didStart..config.didEnd
        emit(DidScanEvent.Started(config.ecus.size, config.dids?.size ?: (config.didEnd - config.didStart + 1)))
        configureAdapter()

        val results = mutableListOf<DidProbeResult>()
        val classifications = mutableListOf<EcuClassification>()
        var probed = 0
        var skipped = 0
        var exhausted = false
        var sinceProgress = 0

        ecuLoop@ for (ecu in config.ecus) {
            currentCoroutineContext().ensureActive()
            emit(DidScanEvent.EcuStarted(ecu))
            val header = ecu.requestHeader.uppercase()
            raw.transceive("AT SH $header")
            config.sessionControl?.let {
                raw.transceive(it)
                delay(config.interProbeDelayMs)
            }

            var ecuHits = 0
            val dead = IntRangeSet()
            val hitDids = mutableListOf<Int>()
            val retryable = linkedMapOf<Int, Int>()

            for (did in targets) {
                currentCoroutineContext().ensureActive()
                if (probed >= config.maxProbes) {
                    exhausted = true
                    break@ecuLoop
                }
                if (config.skip(header, did)) {
                    skipped++
                    // Heartbeat through skipped (dead) runs too, so a progress bar advances instead
                    // of freezing across long stretches the skip-set elides.
                    if (sinceProgress++ % PROGRESS_EVERY == 0) {
                        emit(DidScanEvent.Progress(ecu, did, probed, results.count { it.isHit }, skipped))
                    }
                    continue
                }

                val result = DidResponseParser.parse(did, raw.transceive("22 %04X".format(did))).toResult(ecu, did)
                probed++
                classify(result, hitDids, dead, retryable)

                if (result.isHit) {
                    ecuHits++
                    results += result
                    emit(DidScanEvent.Hit(result))
                } else if (config.keepNonHits) {
                    results += result
                }

                if (sinceProgress++ % PROGRESS_EVERY == 0) {
                    emit(DidScanEvent.Progress(ecu, did, probed, results.count { it.isHit }, skipped))
                }
                delay(config.interProbeDelayMs)
            }

            val classification = EcuClassification(ecu, hitDids.toList(), dead.pairs(), retryable.toMap())
            classifications += classification
            emit(DidScanEvent.EcuClassified(classification))
            emit(DidScanEvent.EcuFinished(ecu, ecuHits))
        }

        emit(DidScanEvent.Finished(results, probed, exhausted, skipped, classifications))
    }

    /** Sort one probe into the per-ECU buckets the skip-memory is built from. */
    private fun classify(
        result: DidProbeResult,
        hits: MutableList<Int>,
        dead: IntRangeSet,
        retryable: MutableMap<Int, Int>,
    ) {
        when (result.kind) {
            DidResponseKind.POSITIVE -> hits += result.did
            DidResponseKind.NEGATIVE ->
                if (result.negativeCode == NRC_REQUEST_OUT_OF_RANGE) dead.add(result.did, result.did)
                else retryable[result.did] = result.negativeCode ?: NRC_NO_DATA
            DidResponseKind.NO_DATA -> retryable[result.did] = NRC_NO_DATA
            DidResponseKind.ERROR, DidResponseKind.EMPTY -> Unit // inconclusive — leave to re-probe
        }
    }

    /**
     * Probe a small set of well-known DIDs against each header to find which ECUs are alive, without
     * sweeping the whole 0x22 space. Returns the headers that gave at least one positive/negative
     * reply (a negative still proves the ECU is present and speaks UDS).
     */
    suspend fun discoverEcus(
        headers: List<EcuTarget> = LixiangEcuSeeds.discoveryHeaders,
        probeDids: List<Int> = LixiangEcuSeeds.WellKnownDids.all,
        interProbeDelayMs: Long = 80L,
    ): List<DidProbeResult> {
        configureAdapter()
        val found = mutableListOf<DidProbeResult>()
        for (ecu in headers) {
            currentCoroutineContext().ensureActive()
            raw.transceive("AT SH ${ecu.requestHeader.uppercase()}")
            for (did in probeDids) {
                val result = DidResponseParser.parse(did, raw.transceive("22 %04X".format(did))).toResult(ecu, did)
                val present = result.kind == DidResponseKind.POSITIVE || result.kind == DidResponseKind.NEGATIVE
                if (present) {
                    found += result
                    break // one confirming reply per ECU is enough
                }
                delay(interProbeDelayMs)
            }
        }
        return found
    }

    /**
     * Poll a fixed set of DIDs in a loop and emit each reading — the correlation aid. Pin a handful
     * of candidate DIDs and watch their values move as you charge/drive/warm the car, instead of
     * diffing whole sweeps. Runs until the collector is cancelled. Targets keep their given order;
     * `AT SH` is only re-sent when the addressed ECU changes, so grouping by ECU minimizes overhead.
     *
     * Pass [refPids] to also read standard mode-`01` references (speed, RPM…) each round — addressed
     * to the functional broadcast so the engine/EV ECU answers — so a logged time-series can line a
     * candidate DID up against a known signal. Each round ends with a [DidWatchEvent.RoundDone].
     */
    fun watch(
        targets: List<DidWatchTarget>,
        refPids: List<RefPid> = emptyList(),
        intervalMs: Long = 500L,
    ): Flow<DidWatchEvent> = flow {
        configureAdapter()
        var currentHeader: String? = null
        var round = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            for (t in targets) {
                currentCoroutineContext().ensureActive()
                val header = t.ecu.requestHeader.uppercase()
                if (header != currentHeader) {
                    raw.transceive("AT SH $header")
                    currentHeader = header
                }
                val result = DidResponseParser.parse(t.did, raw.transceive("22 %04X".format(t.did)))
                    .toResult(t.ecu, t.did)
                emit(DidWatchEvent.Did(DidWatchSample(t, result, round)))
            }
            if (refPids.isNotEmpty()) {
                if (currentHeader != FUNCTIONAL_HEADER) {
                    raw.transceive("AT SH $FUNCTIONAL_HEADER")
                    currentHeader = FUNCTIONAL_HEADER
                }
                for (rp in refPids) {
                    currentCoroutineContext().ensureActive()
                    val value = ReferencePidReader.read(rp, raw.transceive("01 ${rp.pid}"))
                    emit(DidWatchEvent.Ref(RefReading(rp.key, rp.label, rp.unit, value), round))
                }
            }
            emit(DidWatchEvent.RoundDone(round))
            round++
            delay(intervalMs)
        }
    }

    /** Spaces on + headers on so replies tokenize cleanly and carry the responder's id. */
    private suspend fun configureAdapter() {
        try {
            raw.transceive("AT E0") // echo off
            raw.transceive("AT L0") // linefeeds off
            raw.transceive("AT S1") // spaces ON — bytes tokenize cleanly for the parser
            raw.transceive("AT H1") // headers ON — capture which ECU replied
            raw.transceive("AT CAF1") // CAN auto-formatting on — adapter does ISO-TP + flow control
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // Adapter quirks on AT setup shouldn't abort the scan; probes will reveal connectivity.
        }
    }

    private companion object {
        const val PROGRESS_EVERY = 64
        const val NRC_REQUEST_OUT_OF_RANGE = 0x31
        const val FUNCTIONAL_HEADER = "7DF" // OBD functional broadcast — for standard mode-01 reads
    }
}

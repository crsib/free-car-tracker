package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Persisted skip-memory: per scan context, per ECU, what each DID classified as. */
@Serializable
data class DidScanMemoryDoc(val contexts: Map<String, ContextMem> = emptyMap())

@Serializable
data class ContextMem(val ecus: Map<String, EcuMem> = emptyMap())

@Serializable
data class EcuMem(
    /** Confirmed `0x31` DIDs as run-length `[start,end]` pairs (the bulk of the space). */
    val dead: List<List<Int>> = emptyList(),
    /** "Maybe later" DIDs: `didHex -> NRC` (or [NRC_NO_DATA]). Re-probe these in a new state. */
    val retryable: Map<String, Int> = emptyMap(),
    /** Known-good DIDs (`didHex`). */
    val hits: List<String> = emptyList(),
)

/**
 * Persists what DID sweeps have learned so repeat scans skip the dead space and target the unknowns.
 * Scoped by [ScanContext] (state + session): a DID dead while parked may answer while driving, so
 * "dead" is remembered per context, never globally.
 *
 * Backed by a single JSON file. The dead-set is stored run-length encoded, so even a fully-swept
 * 64K space per ECU is a handful of ranges on disk.
 */
class DidScanMemoryStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): DidScanMemoryDoc =
        runCatching { json.decodeFromString<DidScanMemoryDoc>(file.readText()) }.getOrDefault(DidScanMemoryDoc())

    fun save(doc: DidScanMemoryDoc) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(doc))
    }

    /**
     * Skip predicate for [ctx]: skip a DID only if it was confirmed dead (`0x31`) here *and* isn't
     * currently known as a hit or retryable (firmware that flips a DID to alive must still be re-read).
     */
    fun skipPredicate(doc: DidScanMemoryDoc, ctx: ScanContext): (String, Int) -> Boolean {
        val ctxMem = doc.contexts[ctx.key] ?: return { _, _ -> false }
        val perEcu = ctxMem.ecus.mapValues { (_, m) ->
            val dead = IntRangeSet.fromPairs(m.dead.mapNotNull { it.toPair() })
            val alive = (m.hits.toDids() + m.retryable.keys.toDids()).toHashSet()
            dead to alive
        }
        return { header, did ->
            perEcu[header.uppercase()]?.let { (dead, alive) -> dead.contains(did) && did !in alive } ?: false
        }
    }

    /** DIDs worth re-probing in a new state: every hit + retryable across all contexts, ascending. */
    fun interestingDids(doc: DidScanMemoryDoc): List<Int> =
        doc.contexts.values
            .flatMap { it.ecus.values }
            .flatMap { it.hits.toDids() + it.retryable.keys.toDids() }
            .distinct()
            .sorted()

    /** Merge a scan's [classifications] into [ctx] and persist; returns the updated doc. */
    fun merge(doc: DidScanMemoryDoc, ctx: ScanContext, classifications: List<EcuClassification>): DidScanMemoryDoc {
        if (classifications.isEmpty()) return doc
        val ecus = (doc.contexts[ctx.key]?.ecus ?: emptyMap()).toMutableMap()
        for (c in classifications) {
            val key = c.ecu.requestHeader.uppercase()
            val prev = ecus[key] ?: EcuMem()

            val dead = IntRangeSet.fromPairs(prev.dead.mapNotNull { it.toPair() })
            c.deadRanges.forEach { dead.add(it.first, it.second) }

            val hits = (prev.hits.toDids() + c.hits).toSortedSet()
            val retry = prev.retryable.toMutableMap()
            c.retryable.forEach { (d, nrc) -> retry[d.hex()] = nrc }
            hits.forEach { retry.remove(it.hex()) } // a hit supersedes a stale retryable

            ecus[key] = EcuMem(
                dead = dead.pairs().map { listOf(it.first, it.second) },
                retryable = retry,
                hits = hits.map { it.hex() },
            )
        }
        val updated = doc.copy(contexts = doc.contexts + (ctx.key to ContextMem(ecus)))
        save(updated)
        return updated
    }

    private fun List<Int>.toPair(): Pair<Int, Int>? = if (size == 2) this[0] to this[1] else null
    private fun Collection<String>.toDids(): List<Int> = mapNotNull { it.toIntOrNull(16) }
    private fun Int.hex(): String = "%04X".format(this)
}

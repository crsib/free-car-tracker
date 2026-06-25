package me.crsib.freecartracker.telemetry.obd.scan

import me.crsib.freecartracker.data.backup.csvEscape
import me.crsib.freecartracker.data.backup.num

/**
 * Builds the synchronized watch time-series CSV: one row per poll round, with the reference PIDs
 * (decoded engineering values) and the watched DIDs (raw payload hex) side by side under a shared
 * timestamp. This is the artifact you plot — line a DID's payload up against the speed column to
 * spot the one that tracks it.
 *
 * Columns: `timestampMillis, <refLabel>[unit]…, <ECU:DID>…`. Fixed up front so every row aligns.
 */
object DidWatchCsv {
    fun header(refPids: List<RefPid>, targetKeys: List<String>): String =
        (listOf("timestampMillis") +
            refPids.map { "${it.label}[${it.unit}]" } +
            targetKeys).joinToString(",") { csvEscape(it) }

    fun row(
        timestampMillis: Long,
        refValues: Map<String, Double?>,
        didValues: Map<String, String>,
        refPids: List<RefPid>,
        targetKeys: List<String>,
    ): String {
        val cells = buildList {
            add(timestampMillis.toString())
            refPids.forEach { add(refValues[it.key]?.let(::num) ?: "") }
            targetKeys.forEach { add(didValues[it] ?: "") }
        }
        return cells.joinToString(",") { csvEscape(it) }
    }
}

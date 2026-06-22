package me.crsib.freecartracker.data.sync

import me.crsib.freecartracker.data.backup.splitCsvLine
import me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample
import me.crsib.freecartracker.telemetry.data.entity.ObdSample

/**
 * Parses an [ObdCsvExporter] CSV back into standard + extended samples. The header drives column
 * mapping (not fixed positions), so reordered columns and unknown future standard columns are
 * tolerated — unknown plain columns are simply ignored. A blank cell restores null.
 */
object ObdCsvParser {
    fun parse(bytes: ByteArray, tripId: Long): Pair<List<ObdSample>, List<ObdExtendedSample>> {
        val lines = bytes.decodeToString().split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList<ObdSample>() to emptyList()

        val header = splitCsvLine(lines[0])
        val tsIdx = header.indexOf(ObdCsvSchema.TIMESTAMP)
        require(tsIdx >= 0) { "OBD CSV missing ${ObdCsvSchema.TIMESTAMP} column" }

        // Pre-resolve each column to either a standard field, an extended (key,unit), or ignored.
        val stdCols = header.mapIndexed { i, h -> i to ObdCsvSchema.byName[h] }
            .filter { it.second != null }
            .map { it.first to it.second!! }
        val extCols = header.mapIndexedNotNull { i, h ->
            ObdCsvSchema.parseExtHeader(h)?.let { (key, unit) -> Triple(i, key, unit) }
        }

        val samples = ArrayList<ObdSample>()
        val extended = ArrayList<ObdExtendedSample>()
        for (line in lines.drop(1)) {
            val cells = splitCsvLine(line)
            val ts = cells.getOrNull(tsIdx)?.toLongOrNull() ?: continue

            var sample = ObdSample(tripId = tripId, timestampMillis = ts)
            var hasStd = false
            for ((idx, col) in stdCols) {
                val v = cells.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: continue
                sample = col.set(sample, v)
                hasStd = true
            }
            if (hasStd) samples += sample

            for ((idx, key, unit) in extCols) {
                val v = cells.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: continue
                extended += ObdExtendedSample(tripId = tripId, timestampMillis = ts, metricKey = key, value = v, unit = unit)
            }
        }
        return samples to extended
    }
}

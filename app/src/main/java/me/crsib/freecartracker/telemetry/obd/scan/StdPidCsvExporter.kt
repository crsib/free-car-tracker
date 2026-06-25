package me.crsib.freecartracker.telemetry.obd.scan

import me.crsib.freecartracker.data.backup.csvEscape
import me.crsib.freecartracker.data.backup.num

/**
 * Exports a standard-PID sweep to CSV. Includes a few candidate decodings of the leading bytes so a
 * known value can be matched to its PID at a glance — e.g. scan the `AB/20` column for ~370 (pack
 * voltage) or the `A*100/255` column for the SoC %.
 */
object StdPidCsvExporter {
    private val HEADER = listOf(
        "pid", "kind", "responder", "payloadHex",
        "A", "A*100/255", "A-40", "AB", "AB/10", "AB/20", "AB*0.05-1600", "raw",
    )

    fun export(results: List<StdPidResult>): ByteArray {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { csvEscape(it) }).append('\n')
        for (r in results) {
            val bytes = r.payloadHex.chunked(2).mapNotNull { it.toIntOrNull(16) }
            val a = bytes.getOrNull(0)
            val ab = if (bytes.size >= 2) (bytes[0] shl 8) or bytes[1] else null
            val cells = listOf(
                "01${r.pidHex}",
                r.kind.name,
                r.responderHeader ?: "",
                r.payloadHex,
                a?.let { num(it.toDouble()) } ?: "",
                a?.let { num(it * 100.0 / 255.0) } ?: "",
                a?.let { num(it - 40.0) } ?: "",
                ab?.let { num(it.toDouble()) } ?: "",
                ab?.let { num(it / 10.0) } ?: "",
                ab?.let { num(it / 20.0) } ?: "",
                ab?.let { num(it * 0.05 - 1600) } ?: "",
                r.raw.replace('\n', ' ').replace('\r', ' '),
            )
            sb.append(cells.joinToString(",") { csvEscape(it) }).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

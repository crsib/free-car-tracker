package me.crsib.freecartracker.telemetry.obd.scan

import me.crsib.freecartracker.data.backup.csvEscape

/**
 * Exports a recorded init sequence to CSV (`header,command,label,raw`) — the "init map". The honeypot
 * replay loads this and answers each command Car Scanner sends with the real car's recorded bytes,
 * so the experimental Li profile engages offline.
 */
object InitMapCsvExporter {
    private val HEADER = listOf("header", "command", "label", "raw")

    fun export(responses: List<InitResponse>): ByteArray {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { csvEscape(it) }).append('\n')
        for (r in responses) {
            val cells = listOf(
                r.header ?: "",
                r.command,
                r.label,
                r.raw.replace('\n', ' ').replace('\r', ' '),
            )
            sb.append(cells.joinToString(",") { csvEscape(it) }).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

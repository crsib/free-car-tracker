package me.crsib.freecartracker.telemetry.obd.scan

import me.crsib.freecartracker.data.backup.csvEscape

/**
 * Exports discovered DIDs to CSV for offline analysis — the artifact you correlate against known
 * vehicle state (charge while plugged in, temps when cold, etc.) to label each DID, then encode as a
 * [me.crsib.freecartracker.telemetry.obd.pid.PidProfile]. One row per probe result.
 */
object DidScanCsvExporter {
    private val HEADER = listOf(
        "ecuName", "requestHeader", "responderHeader", "did", "kind",
        "negativeCode", "multiFrame", "payloadHex", "raw",
    )

    fun export(results: List<DidProbeResult>): ByteArray {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { csvEscape(it) }).append('\n')
        for (r in results) {
            val cells = listOf(
                r.ecu.name,
                r.ecu.requestHeader,
                r.responderHeader ?: "",
                r.didHex,
                r.kind.name,
                r.negativeCode?.let { "%02X".format(it) } ?: "",
                if (r.multiFrame) "1" else "0",
                r.payloadHex,
                r.raw.replace('\n', ' ').replace('\r', ' '),
            )
            sb.append(cells.joinToString(",") { csvEscape(it) }).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

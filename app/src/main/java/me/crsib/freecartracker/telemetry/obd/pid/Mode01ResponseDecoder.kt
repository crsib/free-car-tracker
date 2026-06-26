package me.crsib.freecartracker.telemetry.obd.pid

/**
 * Extracts the service-data bytes of a Mode 01 reply from raw ELM327 text, as captured on the
 * trip-recording adapter config (echo/headers/spaces OFF). Two real-world framings corrupt the
 * library's naive byte parser ([com.github.eltonvs.obd.command.ObdRawResponse.bufferedValue],
 * which just strips whitespace and folds the lot), and both are handled here:
 *
 *  - **Several ECUs answer one broadcast PID.** Each responder prints its own `41 <pid> …` line;
 *    the library concatenates them and folds [com.github.eltonvs.obd.command.bytesToInt] across
 *    every responder's bytes — e.g. the five ECUs answering `0142` produced a ~1e15 "voltage".
 *    We take the first responder's frame.
 *  - **Long replies arrive as an ISO-TP multi-frame block.** ELM renders it as a bare length line
 *    (`008`) followed by sequence-prefixed continuation lines (`0:…`, `1:…`). Stripping the spaces
 *    and colons leaves the length and frame-index nibbles in the byte stream, shifting the payload
 *    two bytes — which is why HV `019A` decoded as 28 V / 2392 A instead of 373.8 V / 2.5 A. We
 *    reassemble the `N:` frames in order.
 *
 * Returns the bytes *after* the `41 <pid>` echo, or null when no line/frame echoes this PID.
 */
internal object Mode01ResponseDecoder {

    /** A continuation line of an ELM multi-frame block, e.g. `0:419A07005D7300`. */
    private val SEQUENCE_LINE = Regex("^[0-9A-F]+:")

    fun dataBytes(raw: String, pid: String): IntArray? {
        val echoPid = pid.toIntOrNull(16) ?: return null
        val lines = raw.uppercase()
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        // ISO-TP multi-frame block: reassemble the `N:`-prefixed continuation lines in order,
        // ignoring the bare length line that precedes them.
        val sequenced = lines.filter { SEQUENCE_LINE.containsMatchIn(it) }
        if (sequenced.isNotEmpty()) {
            val reassembled = sequenced
                .sortedBy { it.substringBefore(':').toIntOrNull(16) ?: 0 }
                .joinToString("") { it.substringAfter(':') }
            return afterEcho(toBytes(reassembled), echoPid)
        }

        // Single-frame replies (one or many ECUs): the first responder that echoes this PID wins.
        for (line in lines) {
            afterEcho(toBytes(line), echoPid)?.let { return it }
        }
        return null
    }

    private fun afterEcho(bytes: IntArray, echoPid: Int): IntArray? =
        if (bytes.size >= 2 && bytes[0] == 0x41 && bytes[1] == echoPid) {
            bytes.copyOfRange(2, bytes.size)
        } else {
            null
        }

    private fun toBytes(text: String): IntArray {
        val hex = text.filter { it in '0'..'9' || it in 'A'..'F' }
        if (hex.length < 2) return IntArray(0)
        return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }
}

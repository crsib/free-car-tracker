package me.crsib.freecartracker.telemetry.obd.scan

/**
 * Turns raw ELM327 text into the reassembled service-layer bytes, shared by the UDS `0x22` parser
 * ([DidResponseParser]) and the standard mode-`01` reference-PID reader ([ReferencePidReader]).
 *
 * Tuned for the scanner's adapter config — spaces on (`AT S1`) and headers on (`AT H1`) — but
 * tolerant of headers-off and missing-spaces replies. ISO-TP single-frame replies decode directly;
 * first-frame + consecutive-frame multi-frame replies are reassembled to the declared length.
 */
internal object UdsFrames {

    /** Reassembled bytes of one response message, plus framing metadata. */
    data class Decoded(val bytes: List<Int>, val multiFrame: Boolean, val responder: String?)

    /** Decode raw adapter text into service-layer bytes, or null if there are no parseable frames. */
    fun decode(raw: String): Decoded? {
        val frames = raw.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .map(::tokenizeFrame)
            .filter { it.bytes.isNotEmpty() }
            .toList()
        if (frames.isEmpty()) return null
        val (bytes, multiFrame) = reassemble(frames) ?: return null
        return Decoded(bytes, multiFrame, frames.first().canId)
    }

    fun bytesToHex(bytes: List<Int>): String = bytes.joinToString("") { "%02X".format(it and 0xFF) }

    /** One CAN frame: an optional arbitration id followed by its byte payload (PCI + data). */
    private data class Frame(val canId: String?, val bytes: List<Int>)

    /**
     * Split a line into (canId?, bytes). With `AT H1` the first token is the arbitration id —
     * 3 hex chars for 11-bit, 8 for 29-bit. Any other token is a data byte (2 hex chars). When
     * spaces are off, the whole line is one run of hex which we slice into byte pairs (no id).
     */
    private fun tokenizeFrame(line: String): Frame {
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size > 1) {
            val first = tokens.first()
            val isCanId = (first.length == 3 || first.length == 8) && first.isHex()
            val canId = if (isCanId) first else null
            val byteTokens = if (isCanId) tokens.drop(1) else tokens
            return Frame(canId, byteTokens.mapNotNull { it.toIntOrNull(16) })
        }
        val run = tokens.firstOrNull()?.takeIf { it.isHex() } ?: return Frame(null, emptyList())
        return Frame(null, run.chunked(2).mapNotNull { it.toIntOrNull(16) })
    }

    /**
     * Reassemble ISO-TP frames into the service payload. Returns (bytes, wasMultiFrame), or null if
     * the leading PCI is malformed. SF carries its length in the low nibble; FF carries a 12-bit
     * length and is followed by CFs whose first byte is a 0x2N sequence marker.
     */
    private fun reassemble(frames: List<Frame>): Pair<List<Int>, Boolean>? {
        val first = frames.first().bytes
        if (first.isEmpty()) return null
        return when (first[0] shr 4) {
            0x0 -> { // single frame
                val len = first[0] and 0x0F
                (first.drop(1).take(len)) to false
            }
            0x1 -> { // first frame of a multi-frame message
                if (first.size < 2) return null
                val len = ((first[0] and 0x0F) shl 8) or first[1]
                val out = ArrayList<Int>(len)
                out.addAll(first.drop(2))
                for (cf in frames.drop(1)) {
                    if (cf.bytes.isNotEmpty() && (cf.bytes[0] shr 4) == 0x2) out.addAll(cf.bytes.drop(1))
                }
                out.take(len) to true
            }
            else -> first to false // unframed/unexpected — hand back raw bytes
        }
    }

    private fun String.isHex(): Boolean = isNotEmpty() && all { it in '0'..'9' || it in 'A'..'F' }
}

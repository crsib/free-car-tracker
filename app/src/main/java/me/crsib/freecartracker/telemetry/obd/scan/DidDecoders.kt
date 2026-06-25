package me.crsib.freecartracker.telemetry.obd.scan

/**
 * Eyeballing aids for live-watch mode. We don't know a discovered DID's scaling yet, so instead of
 * committing to one decode we show a few plausible interpretations of the leading bytes side by
 * side. Whichever reading tracks a known physical change (SoC while charging, temp while warming) is
 * the scaling to encode in a [me.crsib.freecartracker.telemetry.obd.pid.PidProfile].
 */
object DidDecoders {

    /**
     * A compact one-line hint for a payload, e.g. `A=128 (50.2% · 88°C · /2=64) · AB=0E74 (3700)`.
     * Empty when there's no payload to interpret.
     */
    fun hint(payloadHex: String): String {
        val bytes = bytesOf(payloadHex)
        if (bytes.isEmpty()) return ""
        val parts = mutableListOf<String>()

        val a = bytes[0]
        parts += "A=$a (" + listOf(
            "%.1f%%".format(a * 100.0 / 255.0),
            "${a - 40}°C",
            "/2=${"%.1f".format(a / 2.0)}",
        ).joinToString(" · ") + ")"

        if (bytes.size >= 2) {
            val ab = (bytes[0] shl 8) or bytes[1]
            parts += "AB=%04X (%d · /10=%.1f)".format(ab, ab, ab / 10.0)
        }
        return parts.joinToString(" · ")
    }

    private fun bytesOf(payloadHex: String): List<Int> =
        payloadHex.trim().chunked(2).mapNotNull { it.toIntOrNull(16) }
}

/**
 * Parse free-text watch targets into [DidWatchTarget]s. Accepts one entry per line or
 * comma-separated, each `HEADER DID` or `HEADER:DID` (e.g. `7E8 2002`, `7E0:F190`). The DID may be
 * 1–4 hex digits. Lines that don't parse are skipped. The label on each target's ECU is `ECU_<hdr>`.
 */
fun parseWatchTargets(text: String): List<DidWatchTarget> =
    text.split('\n', ',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(Regex("[\\s:]+")).filter { it.isNotEmpty() }
            if (parts.size != 2) return@mapNotNull null
            val header = parts[0].uppercase()
            val did = parts[1].toIntOrNull(16) ?: return@mapNotNull null
            val headerOk = (header.length == 3 || header.length == 8) &&
                header.all { it in '0'..'9' || it in 'A'..'F' }
            if (!headerOk || did !in 0x0000..0xFFFF) return@mapNotNull null
            DidWatchTarget(EcuTarget(name = "ECU_$header", requestHeader = header), did = did)
        }
        .distinctBy { it.key }

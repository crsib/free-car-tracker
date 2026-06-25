package me.crsib.freecartracker.telemetry.obd.scan

/**
 * Classifies the raw ELM327 text for one UDS `22 <did>` request into a [DidProbeResult]. Pure and
 * deterministic — this is the heart of the scanner and the part worth testing hardest.
 *
 * Designed for the scanner's adapter config: **spaces on** (`AT S1`, so bytes tokenize cleanly) and
 * **headers on** (`AT H1`, so the responding arbitration id is the first token). It also tolerates
 * headers-off replies (no id prefix) and missing spaces, so it degrades gracefully.
 *
 * ISO-TP framing is handled: single-frame (SF) replies decode directly; first-frame (FF) + the
 * consecutive frames (CF) the adapter returns under auto-flow-control are reassembled to the
 * declared length.
 */
object DidResponseParser {

    private val ERROR_MARKERS = listOf(
        "BUSINIT", "BUSERROR", "CANERROR", "FBERROR", "DATAERROR",
        "STOPPED", "UNABLETOCONNECT", "ERROR", "?",
    )

    fun parse(requestedDid: Int, raw: String): DidProbeResult.Partial {
        val collapsed = raw.uppercase().replace(Regex("\\s"), "")
        if (collapsed.isEmpty()) return DidResponseKind.EMPTY.partial(raw)
        if ("NODATA" in collapsed) return DidResponseKind.NO_DATA.partial(raw)
        if (ERROR_MARKERS.any { it in collapsed }) return DidResponseKind.ERROR.partial(raw)

        val decoded = UdsFrames.decode(raw) ?: return DidResponseKind.EMPTY.partial(raw)
        val uds = decoded.bytes
        val responder = decoded.responder

        return when {
            uds.size >= 1 && uds[0] == 0x62 -> {
                // Positive: 62 <didHi> <didLo> <data…>. Echo is 3 bytes; payload is the rest.
                val payload = if (uds.size > 3) uds.drop(3) else emptyList()
                DidProbeResult.Partial(
                    kind = DidResponseKind.POSITIVE,
                    responderHeader = responder,
                    payloadHex = UdsFrames.bytesToHex(payload),
                    multiFrame = decoded.multiFrame,
                    raw = raw.trim(),
                )
            }
            uds.size >= 3 && uds[0] == 0x7F && uds[1] == 0x22 ->
                DidProbeResult.Partial(
                    kind = DidResponseKind.NEGATIVE,
                    responderHeader = responder,
                    negativeCode = uds[2],
                    raw = raw.trim(),
                )
            else -> DidResponseKind.ERROR.partial(raw, responder)
        }
    }

    private fun DidResponseKind.partial(raw: String, responder: String? = null) =
        DidProbeResult.Partial(kind = this, responderHeader = responder, raw = raw.trim())
}

/**
 * Promote a parser [DidProbeResult.Partial] to a full result by attaching the (ecu, did) context
 * the scanner owns.
 */
fun DidProbeResult.Partial.toResult(ecu: EcuTarget, did: Int): DidProbeResult = DidProbeResult(
    ecu = ecu,
    did = did,
    kind = kind,
    responderHeader = responderHeader,
    negativeCode = negativeCode,
    payloadHex = payloadHex,
    multiFrame = multiFrame,
    raw = raw,
)

package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One init/identification probe: an optional ECU header (`AT SH`) plus the command to send. */
data class InitProbe(val header: String?, val command: String, val label: String = command)

/** The real car's response to one init probe — the bytes the honeypot replays back to Car Scanner. */
data class InitResponse(val header: String?, val command: String, val label: String, val raw: String)

sealed interface InitRecordEvent {
    data class Probed(val response: InitResponse, val index: Int, val total: Int) : InitRecordEvent
    data class Finished(val responses: List<InitResponse>) : InitRecordEvent
}

/**
 * Records the real car's responses to the OBD init / identification sequence Car Scanner uses — the
 * supported-PID bitmasks, the Li Auto `22 1201` identification, and the VIN. Replaying these through
 * the capture honeypot makes Car Scanner believe it's the real car, so its **experimental Li profile
 * engages offline** — which a faked identification can't do. The recorded responses are the "init
 * map" the honeypot replay loads. It does NOT sweep sensors; it captures exactly the handshake.
 */
class InitSequenceRecorder(private val raw: RawObd) {

    fun record(probes: List<InitProbe> = DEFAULT_PROBES, interProbeDelayMs: Long = 60L): Flow<InitRecordEvent> = flow {
        configure()
        val out = mutableListOf<InitResponse>()
        var currentHeader: String? = null
        probes.forEachIndexed { i, p ->
            currentCoroutineContext().ensureActive()
            val h = p.header?.uppercase()
            if (h != null && h != currentHeader) {
                raw.transceive("AT SH $h")
                // For physical addressing, set up flow control so multi-frame replies (e.g. the
                // 22 1201 identification) come back complete — Car Scanner does exactly this. The
                // ELM's auto-FC on the functional broadcast handles those reads (VIN) on its own.
                if (h != FUNCTIONAL) {
                    raw.transceive("AT FC SH $h")
                    raw.transceive("AT FC SD 30 00 00")
                    raw.transceive("AT FC SM 1")
                }
                currentHeader = h
            }
            val resp = InitResponse(p.header, p.command, p.label, raw.transceive(p.command).trim())
            out += resp
            emit(InitRecordEvent.Probed(resp, i + 1, probes.size))
            delay(interProbeDelayMs)
        }
        emit(InitRecordEvent.Finished(out))
    }

    /** Spaces + headers on so the replay map keeps responder ids and tokenizes cleanly. */
    private suspend fun configure() {
        try {
            raw.transceive("AT E0")
            raw.transceive("AT L0")
            raw.transceive("AT S1")
            raw.transceive("AT H1")
            raw.transceive("AT CAF1")
            // Warm-up: the first OBD command triggers the protocol search, which our raw read can
            // cut short — so lock the protocol here (discarded) before the real probes.
            raw.transceive("0100")
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // AT quirks shouldn't abort the recording.
        }
    }

    companion object {
        private const val FUNCTIONAL = "7DF" // OBD functional broadcast (standard PIDs / VIN)
        private const val LI_ECU = "7E0"     // Li Auto identification ECU (seen in the honeypot capture)

        /** Mirrors Car Scanner's handshake on a real Li Auto — enough for the honeypot to replay it. */
        val DEFAULT_PROBES: List<InitProbe> = buildList {
            // Supported-PID bitmasks (so Car Scanner sees the same "supported" set as the real car).
            listOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "01E0")
                .forEach { add(InitProbe(FUNCTIONAL, it)) }
            // VIN only. (0900/0904/090A flood 8 ECUs with multi-frame data and overflow the ELM
            // buffer — `BUFFER FULL` — which desyncs the following reads, so they're omitted.)
            add(InitProbe(FUNCTIONAL, "0902"))
            // Li Auto identification DIDs (physical 7E0) — the part that makes the profile engage.
            listOf("221201", "2212010401", "22F190", "22F18C", "22F195", "22F197")
                .forEach { add(InitProbe(LI_ECU, it)) }
        }
    }
}

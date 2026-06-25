package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How a standard mode-01 PID answered an `01 xx` probe. */
enum class StdPidKind { SUPPORTED, NO_DATA, ERROR }

/** Result of probing one standard mode-01 PID. */
data class StdPidResult(
    val pid: Int,
    val kind: StdPidKind,
    /** Data bytes after the `41 <pid>` echo, as contiguous hex. */
    val payloadHex: String = "",
    val responderHeader: String? = null,
    val raw: String = "",
) {
    val pidHex: String get() = "%02X".format(pid)
    val isSupported: Boolean get() = kind == StdPidKind.SUPPORTED
}

sealed interface StdPidScanEvent {
    data class Hit(val result: StdPidResult) : StdPidScanEvent
    data class Progress(val pid: Int, val probed: Int, val supported: Int) : StdPidScanEvent
    data class Finished(val results: List<StdPidResult>, val probed: Int) : StdPidScanEvent
}

/**
 * Sweeps standard mode-01 PIDs (`01 00`..`01 FF`) and records which ones respond plus their raw data
 * bytes. Used to pin a metric's PID on a real car when its *meaning* is known from another tool but
 * the *PID number* isn't — e.g. the PID whose 2-byte value decodes to ~370 V is the pack voltage,
 * the one swinging with regen/accel is the pack current. Probes the functional broadcast (`7DF`).
 */
class StandardPidScanner(private val raw: RawObd) {

    fun sweep(start: Int = 0x00, end: Int = 0xFF, interProbeDelayMs: Long = 40L): Flow<StdPidScanEvent> = flow {
        configure()
        val results = mutableListOf<StdPidResult>()
        var probed = 0
        for (pid in start..end) {
            currentCoroutineContext().ensureActive()
            val result = parse(pid, raw.transceive("01 %02X".format(pid)))
            probed++
            results += result
            if (result.isSupported) emit(StdPidScanEvent.Hit(result))
            emit(StdPidScanEvent.Progress(pid, probed, results.count { it.isSupported }))
            delay(interProbeDelayMs)
        }
        emit(StdPidScanEvent.Finished(results, probed))
    }

    private fun parse(pid: Int, reply: String): StdPidResult {
        val collapsed = reply.uppercase().replace(Regex("\\s"), "")
        when {
            collapsed.isEmpty() || "NODATA" in collapsed ->
                return StdPidResult(pid, StdPidKind.NO_DATA, raw = reply.trim())
            ERROR_MARKERS.any { it in collapsed } ->
                return StdPidResult(pid, StdPidKind.ERROR, raw = reply.trim())
        }
        val decoded = UdsFrames.decode(reply) ?: return StdPidResult(pid, StdPidKind.ERROR, raw = reply.trim())
        val b = decoded.bytes
        // Positive mode-01 reply: 41 <pid> <data…>.
        return if (b.size >= 2 && b[0] == 0x41 && b[1] == pid) {
            StdPidResult(pid, StdPidKind.SUPPORTED, UdsFrames.bytesToHex(b.drop(2)), decoded.responder, reply.trim())
        } else {
            StdPidResult(pid, StdPidKind.ERROR, raw = reply.trim())
        }
    }

    /** Spaces + headers on; address the functional broadcast so the ECU answering standard PIDs replies. */
    private suspend fun configure() {
        try {
            raw.transceive("AT E0")
            raw.transceive("AT L0")
            raw.transceive("AT S1")
            raw.transceive("AT H1")
            raw.transceive("AT SH 7DF")
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // AT quirks shouldn't abort the sweep.
        }
    }

    private companion object {
        val ERROR_MARKERS = listOf("ERROR", "?", "STOPPED", "UNABLETOCONNECT", "BUSINIT", "CANERROR")
    }
}

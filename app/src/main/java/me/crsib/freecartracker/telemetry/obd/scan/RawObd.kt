package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * A minimal ELM327 channel: send one command line, return the raw adapter text (prompt stripped).
 *
 * The scanner needs this instead of the OBD library's `ObdDeviceConnection.run`, which routes every
 * reply through `BadResponseException.checkForExceptions` and *throws* on `NO DATA` and negative
 * responses. For discovery those replies are the signal, not an error — so we read the wire directly.
 */
interface RawObd {
    suspend fun transceive(command: String): String
}

/**
 * [RawObd] over an [ObdTransport][me.crsib.freecartracker.telemetry.obd.transport.ObdTransport]'s
 * byte streams. Reads until the ELM `>` prompt, tolerating slow replies.
 *
 * The read uses an **idle timeout that resets on every byte**, not a fixed total deadline: a protocol
 * search or a slow ECU can trickle bytes (and bus-init `.` dots) over several seconds, and we keep
 * reading as long as data keeps coming, all the way to the prompt. A short fixed timeout would cut
 * the reply off mid-flight, and firing the next command into a still-busy ELM makes it answer
 * `STOPPED`. Stale bytes from a prior incomplete read are drained before each command so they don't
 * pollute the next reply.
 *
 * Serialized by a mutex; must not run concurrently with the library connection on the same streams —
 * the scanner takes exclusive use of the adapter while it runs.
 */
class StreamRawObd(
    private val input: InputStream,
    private val output: OutputStream,
    /** Give up once no byte has arrived for this long — the reply is complete or the ELM has stalled. */
    private val idleTimeoutMs: Long = 2_000L,
    /** Hard cap on one transceive, in case the adapter never sends the `>` prompt. */
    private val maxTotalMs: Long = 8_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowNanos: () -> Long = System::nanoTime,
) : RawObd {
    private val mutex = Mutex()

    override suspend fun transceive(command: String): String = mutex.withLock {
        withContext(ioDispatcher) {
            drainStale()
            output.write("$command\r".toByteArray())
            output.flush()
            readUntilPrompt()
        }
    }

    /** Consume any leftover bytes from a previous incomplete read so they don't corrupt this reply. */
    private fun drainStale() {
        while (input.available() > 0) {
            if (input.read() == -1) break
        }
    }

    private suspend fun readUntilPrompt(): String {
        val sb = StringBuilder()
        val hardDeadline = nowNanos() + maxTotalMs * 1_000_000
        var idleDeadline = nowNanos() + idleTimeoutMs * 1_000_000
        while (true) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                val c = b.toChar()
                if (c == PROMPT) break
                sb.append(c)
                idleDeadline = nowNanos() + idleTimeoutMs * 1_000_000 // reset idle on each byte
            } else {
                val now = nowNanos()
                if (now >= idleDeadline || now >= hardDeadline) break
                delay(IDLE_POLL_MS)
            }
        }
        return sanitize(sb.toString())
    }

    /** Strip the ELM `SEARCHING…`/`BUS INIT` chatter and bus-init dots; OBD payloads are hex only. */
    private fun sanitize(raw: String): String =
        raw.replace(SEARCHING, "").replace(BUS_INIT, "").replace(".", "").trim()

    private companion object {
        const val PROMPT = '>'
        const val SEARCHING = "SEARCHING"
        const val BUS_INIT = "BUS INIT"
        const val IDLE_POLL_MS = 20L
    }
}

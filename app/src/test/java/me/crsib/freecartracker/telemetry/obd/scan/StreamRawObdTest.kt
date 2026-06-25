package me.crsib.freecartracker.telemetry.obd.scan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/**
 * Loopback ELM: bytes only become readable after the command's terminating `\r` is written — so it
 * models the real request→response timing (the stream is empty when [StreamRawObd] drains stale
 * bytes, then fills once the command is sent).
 */
private class LoopbackElm(private val response: String) {
    private val pending = ArrayDeque<Int>()
    val input = object : InputStream() {
        override fun available() = pending.size
        override fun read(): Int = if (pending.isEmpty()) -1 else pending.removeFirst()
    }
    val output = object : OutputStream() {
        override fun write(b: Int) {
            if (b.toChar() == '\r') response.forEach { pending.addLast(it.code) }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StreamRawObdTest {

    @Test
    fun transceive_readsToPromptAndStripsSearchingAndDots() = runTest {
        val elm = LoopbackElm("SEARCHING...41 00 BE 3F A8 13\r\r>")
        val raw = StreamRawObd(elm.input, elm.output, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val resp = raw.transceive("0100")

        // SEARCHING + bus-init dots stripped, prompt consumed, payload intact.
        assertEquals("41 00 BE 3F A8 13", resp)
    }

    @Test
    fun transceive_drainsStaleBytesBeforeSending() = runTest {
        // Leftover from a prior incomplete read sits in the buffer before the command goes out.
        val elm = LoopbackElm("41 0D 18\r\r>")
        elm.input.let { /* preload stale bytes */ }
        val stale = "STALE7E8\r".toByteArray()
        val rawWithStale = object : InputStream() {
            val q = ArrayDeque<Int>().apply { stale.forEach { addLast(it.toInt() and 0xFF) } }
            var afterWrite = false
            override fun available() = q.size
            override fun read() = if (q.isEmpty()) -1 else q.removeFirst()
            fun deliver(s: String) = s.forEach { q.addLast(it.code) }
        }
        val out = object : OutputStream() {
            override fun write(b: Int) { if (b.toChar() == '\r') rawWithStale.deliver("41 0D 18\r\r>") }
        }
        val raw = StreamRawObd(rawWithStale, out, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        // The stale "STALE7E8" must be drained, not prepended to the real reply.
        assertEquals("41 0D 18", raw.transceive("010D"))
    }
}

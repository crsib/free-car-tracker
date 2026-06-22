package me.crsib.freecartracker.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.telemetry.obd.transport.ObdTransport
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * In-memory [ObdTransport] that emulates an ELM327. When the OBD library writes a command (ending
 * in '\r') to [output], the matching scripted response (terminated by the ELM327 '>' prompt) is
 * enqueued synchronously so it is ready before the library reads [input]. Thread-safe (a queue,
 * not thread-bound pipes) so it survives the library hopping IO-dispatcher threads.
 *
 * [answers] is keyed by command with spaces removed and upper-cased (e.g. "220105").
 * Unknown AT commands answer "OK"; unknown PIDs answer "NO DATA".
 */
class FakeObdTransport(private val answers: Map<String, String>) : ObdTransport {
    override val kind = ObdTransportKind.WIFI

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val responseBytes = LinkedBlockingQueue<Int>() // each entry is a byte 0..255
    private val commandBuffer = StringBuilder()

    override val input: InputStream = object : InputStream() {
        override fun available(): Int = responseBytes.size
        override fun read(): Int = responseBytes.take()
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            when (val ch = b.toChar()) {
                '\r', '\n' -> {
                    if (commandBuffer.isNotBlank()) enqueueResponse(commandBuffer.toString().trim())
                    commandBuffer.setLength(0)
                }
                else -> commandBuffer.append(ch)
            }
        }
    }

    private fun enqueueResponse(command: String) {
        val key = command.replace(" ", "").uppercase()
        val body = answers[key] ?: when {
            key == "ATZ" -> "ELM327 v1.5"
            key.startsWith("AT") -> "OK"
            else -> "NO DATA"
        }
        "$body\r\r>".forEach { responseBytes.put(it.code) }
    }

    override suspend fun connect() {
        _isConnected.value = true
    }

    override suspend fun close() {
        _isConnected.value = false
    }
}

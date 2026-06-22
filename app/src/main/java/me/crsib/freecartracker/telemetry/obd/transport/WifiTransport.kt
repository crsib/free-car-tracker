package me.crsib.freecartracker.telemetry.obd.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.crsib.freecartracker.domain.model.ObdTransportKind
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** WiFi ELM327 transport — a plain TCP socket (default 192.168.0.10:35000). */
class WifiTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10_000,
) : ObdTransport {
    override val kind = ObdTransportKind.WIFI

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile private var socket: Socket? = null
    override val input: InputStream get() = socket?.getInputStream() ?: error("WiFi transport not connected")
    override val output: OutputStream get() = socket?.getOutputStream() ?: error("WiFi transport not connected")

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.tcpNoDelay = true
        } catch (e: Throwable) {
            runCatching { s.close() } // release the fd if connect fails partway
            throw e
        }
        socket = s
        _isConnected.value = true
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { socket?.close() } }
        socket = null
        _isConnected.value = false
    }
}

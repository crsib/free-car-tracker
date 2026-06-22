package me.crsib.freecartracker.telemetry.obd.transport

import kotlinx.coroutines.flow.StateFlow
import me.crsib.freecartracker.domain.model.ObdTransportKind
import java.io.InputStream
import java.io.OutputStream

/**
 * A bidirectional byte stream to an ELM327 adapter. The OBD library consumes [input]/[output];
 * each implementation owns one wire protocol (Bluetooth Classic, BLE, or WiFi).
 */
interface ObdTransport {
    val kind: ObdTransportKind
    val isConnected: StateFlow<Boolean>

    /** Establish the connection. Suspends until ready, or throws on failure. */
    suspend fun connect()

    /** Valid only after [connect] succeeds. */
    val input: InputStream
    val output: OutputStream

    suspend fun close()
}

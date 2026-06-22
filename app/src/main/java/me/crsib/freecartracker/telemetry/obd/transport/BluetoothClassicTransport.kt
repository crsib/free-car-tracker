package me.crsib.freecartracker.telemetry.obd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.crsib.freecartracker.domain.model.ObdTransportKind
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Bluetooth Classic (RFCOMM/SPP) transport — the most common ELM327 adapter type. */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT verified by caller before connecting
class BluetoothClassicTransport(
    private val adapter: BluetoothAdapter,
    private val address: String,
) : ObdTransport {
    override val kind = ObdTransportKind.BT_CLASSIC

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile private var socket: BluetoothSocket? = null
    override val input: InputStream get() = socket?.inputStream ?: error("BT transport not connected")
    override val output: OutputStream get() = socket?.outputStream ?: error("BT transport not connected")

    override suspend fun connect() = withContext(Dispatchers.IO) {
        adapter.cancelDiscovery() // discovery slows down / breaks RFCOMM connects
        val device = adapter.getRemoteDevice(address)
        val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
        } catch (e: Throwable) {
            runCatching { s.close() } // release the fd if connect fails
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

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

package me.crsib.freecartracker.telemetry.obd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.telemetry.DiagLog
import java.io.IOException
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
        // Cheap ELM327 clones are unreliable about the SPP service record, so try several ways to
        // open the RFCOMM channel, in order of preference:
        //  1. insecure SPP-UUID socket — works for most clones, no bonding/PIN prompt;
        //  2. secure SPP-UUID socket — some paired adapters only accept the authenticated channel;
        //  3. reflection socket on channel 1 — the classic fallback for adapters whose SDP lookup
        //     returns no channel (createRfcommSocketToServiceRecord throws "service discovery failed").
        val factories = listOf<Pair<String, () -> BluetoothSocket>>(
            "insecure-uuid" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "secure-uuid" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "reflection-ch1" to { reflectionRfcommSocket(device) },
        )
        var lastError: Throwable? = null
        for ((label, make) in factories) {
            val s = runCatching { make() }.getOrElse { lastError = it; null } ?: continue
            try {
                // BluetoothSocket.connect() blocks; to a powered-off / out-of-range adapter it can
                // hang far longer than the RFCOMM stack's own timeout. Bound it so the recorder can
                // fall back to RECONNECTING instead of sitting on "Connecting to adapter…" forever.
                // runInterruptible lets the timeout interrupt the blocking call; closing the socket
                // also aborts it.
                withTimeout(CONNECT_TIMEOUT_MS) { runInterruptible { s.connect() } }
                socket = s
                _isConnected.value = true
                return@withContext
            } catch (e: TimeoutCancellationException) {
                // A timeout means the adapter isn't answering at all (off / out of range). Trying
                // the other socket methods would just burn another timeout each — bail and let the
                // recorder back off and retry the whole connect.
                runCatching { s.close() }
                throw e
            } catch (e: Throwable) {
                runCatching { s.close() } // release the fd (and unblock a hung connect) before next method
                lastError = e
                DiagLog.w(TAG, "RFCOMM connect via $label failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw lastError ?: IOException("RFCOMM connect failed for $address")
    }

    /** Hidden-API channel-1 socket: the fallback for adapters with no usable SPP service record. */
    private fun reflectionRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { socket?.close() } }
        socket = null
        _isConnected.value = false
    }

    companion object {
        private const val TAG = "BtClassicTransport"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Upper bound on a single RFCOMM connect attempt before we give up and reconnect. */
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}

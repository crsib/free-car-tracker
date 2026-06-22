package me.crsib.freecartracker.telemetry.obd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import me.crsib.freecartracker.domain.model.ObdTransportKind
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * BLE ELM327 transport. BLE is notification-based, not stream-based, so this bridges both ways:
 * incoming GATT notifications are pushed into a [PipedInputStream] the OBD library reads; library
 * writes are MTU-chunked to the write characteristic, one outstanding GATT op at a time.
 *
 * BLE clones vary wildly in their service/characteristic UUIDs, so those are supplied by config.
 * This is the hardest transport to validate without real hardware.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT verified by caller before connecting
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val serviceUuid: UUID,
    private val notifyUuid: UUID,
    private val writeUuid: UUID,
) : ObdTransport {
    override val kind = ObdTransportKind.BLE

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = DEFAULT_MTU

    // Notification bytes flow in via [pipeSink] and out of [input].
    private val pipeInput = PipedInputStream(PIPE_BUFFER)
    private val pipeSink = PipedOutputStream(pipeInput)

    private var servicesReady: CompletableDeferred<Boolean>? = null
    private val writeAck = ArrayBlockingQueue<Boolean>(1)

    override val input: InputStream get() = pipeInput
    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) =
            writeChunked(b.copyOfRange(off, off + len))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(REQUESTED_MTU)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _isConnected.value = false
                    servicesReady?.takeIf { !it.isCompleted }?.complete(false)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(serviceUuid)
            writeChar = service?.getCharacteristic(writeUuid)
            val notify = service?.getCharacteristic(notifyUuid)
            if (notify != null) {
                g.setCharacteristicNotification(notify, true)
                notify.getDescriptor(CCCD_UUID)?.let { cccd ->
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
            servicesReady?.complete(writeChar != null && notify != null)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid == notifyUuid) runCatching { pipeSink.write(value); pipeSink.flush() }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // Only the write characteristic's completion paces our chunked writes; ignore the
            // CCCD/descriptor and any other characteristic's acks.
            if (ch.uuid == writeUuid) writeAck.offer(true)
        }
    }

    override suspend fun connect() {
        val ready = CompletableDeferred<Boolean>()
        servicesReady = ready
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = try {
            withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            // withTimeout throws a CancellationException subclass; convert it to a plain IO error
            // so the reconnect loop backs off and retries instead of tearing down the whole trip.
            close()
            throw IOException("BLE connect timed out after ${CONNECT_TIMEOUT_MS}ms", e)
        }
        if (!ok) {
            close()
            error("BLE setup failed (service $serviceUuid or characteristics not found)")
        }
        _isConnected.value = true
    }

    private fun writeChunked(data: ByteArray) {
        val g = gatt ?: error("BLE not connected")
        val ch = writeChar ?: error("BLE write characteristic missing")
        val chunkSize = (mtu - ATT_HEADER).coerceAtLeast(MIN_CHUNK)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            writeAck.clear()
            g.writeCharacteristic(
                ch,
                data.copyOfRange(offset, end),
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
            // Pace writes: wait briefly for the stack's ack (also fires for NO_RESPONSE).
            writeAck.poll(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            offset = end
        }
    }

    override suspend fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        runCatching { pipeSink.close() }
        gatt = null
        writeChar = null
        _isConnected.value = false
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 247
        private const val ATT_HEADER = 3
        private const val MIN_CHUNK = 20
        private const val PIPE_BUFFER = 8 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 250L
    }
}

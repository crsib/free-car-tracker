package me.crsib.freecartracker.telemetry.obd.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import me.crsib.freecartracker.domain.model.ObdTransportKind
import java.util.UUID

/** Builds the right [ObdTransport] from a saved [ObdConnectionConfig]. Open for test injection. */
open class TransportFactory(private val appContext: Context) {
    open fun create(config: ObdConnectionConfig): ObdTransport {
        val btManager = appContext.getSystemService(BluetoothManager::class.java)
        return when (config.kind) {
            ObdTransportKind.WIFI ->
                WifiTransport(config.wifiHost, config.wifiPort)

            ObdTransportKind.BT_CLASSIC -> {
                val adapter = btManager?.adapter ?: error("Bluetooth not available")
                val address = config.deviceAddress ?: error("No Bluetooth device selected")
                BluetoothClassicTransport(adapter, address)
            }

            ObdTransportKind.BLE -> {
                val adapter = btManager?.adapter ?: error("Bluetooth not available")
                val address = config.deviceAddress ?: error("No BLE device selected")
                BleTransport(
                    context = appContext,
                    device = adapter.getRemoteDevice(address),
                    serviceUuid = UUID.fromString(config.bleServiceUuid),
                    notifyUuid = UUID.fromString(config.bleNotifyUuid),
                    writeUuid = UUID.fromString(config.bleWriteUuid),
                )
            }

            ObdTransportKind.NONE -> error("NONE is not a connectable transport")
        }
    }
}

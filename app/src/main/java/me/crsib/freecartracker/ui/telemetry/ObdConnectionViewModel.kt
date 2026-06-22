package me.crsib.freecartracker.ui.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.telemetry.data.TelemetrySettingsStore
import me.crsib.freecartracker.telemetry.location.LocationSourceFactory
import me.crsib.freecartracker.telemetry.obd.ObdSession
import me.crsib.freecartracker.telemetry.obd.pid.StandardPidProfile
import me.crsib.freecartracker.telemetry.obd.transport.ObdConnectionConfig
import me.crsib.freecartracker.telemetry.obd.transport.ObdTransport
import me.crsib.freecartracker.telemetry.obd.transport.TransportFactory

data class BtDevice(val name: String, val address: String)

data class ObdConnUiState(
    val config: ObdConnectionConfig = ObdConnectionConfig(),
    val bonded: List<BtDevice> = emptyList(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val savedAt: Long = 0,
)

class ObdConnectionViewModel(
    private val appContext: Context,
    private val settings: TelemetrySettingsStore,
    private val transportFactory: TransportFactory,
    private val locationFactory: LocationSourceFactory,
) : ViewModel() {

    private val _state = MutableStateFlow(ObdConnUiState(config = settings.loadConnection()))
    val state: StateFlow<ObdConnUiState> = _state.asStateFlow()

    private fun updateConfig(block: (ObdConnectionConfig) -> ObdConnectionConfig) =
        _state.update { it.copy(config = block(it.config), testResult = null) }

    fun setKind(kind: ObdTransportKind) = updateConfig { it.copy(kind = kind) }
    fun setWifiHost(host: String) = updateConfig { it.copy(wifiHost = host) }
    fun setWifiPort(port: String) = updateConfig { it.copy(wifiPort = port.toIntOrNull() ?: it.wifiPort) }
    fun selectDevice(device: BtDevice) =
        updateConfig { it.copy(deviceAddress = device.address, deviceName = device.name) }
    fun setBleService(uuid: String) = updateConfig { it.copy(bleServiceUuid = uuid) }
    fun setBleNotify(uuid: String) = updateConfig { it.copy(bleNotifyUuid = uuid) }
    fun setBleWrite(uuid: String) = updateConfig { it.copy(bleWriteUuid = uuid) }

    fun save() {
        settings.saveConnection(_state.value.config)
        _state.update { it.copy(savedAt = System.currentTimeMillis()) }
    }

    @SuppressLint("MissingPermission") // caller requests BLUETOOTH_CONNECT before invoking
    fun refreshBondedDevices() {
        val devices = runCatching {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
            manager?.adapter?.bondedDevices.orEmpty().map {
                BtDevice(name = it.name ?: it.address, address = it.address)
            }
        }.getOrDefault(emptyList())
        _state.update { it.copy(bonded = devices) }
    }

    fun testConnection() {
        if (_state.value.testing) return
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val config = _state.value.config
            val result = withContext(Dispatchers.IO) { runTest(config) }
            _state.update { it.copy(testing = false, testResult = result) }
        }
    }

    private suspend fun runTest(config: ObdConnectionConfig): String {
        var transport: ObdTransport? = null
        return try {
            transport = transportFactory.create(config)
            transport.connect()
            val session = ObdSession(transport)
            session.initialize()
            val supported = runCatching { session.discoverSupportedPids() }.getOrDefault(emptySet())
            val rpm = session.query(StandardPidProfile.pids.first { it.key == "rpm" })
            val rpmText = rpm?.value?.toInt()?.toString() ?: "n/a"
            "Connected. Supported PIDs: ${supported.size}. RPM: $rpmText. " +
                "Location: ${locationFactory.create().name}."
        } catch (e: Exception) {
            "Failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { transport?.close() }
        }
    }
}

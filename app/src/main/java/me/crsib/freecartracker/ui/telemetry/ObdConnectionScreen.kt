package me.crsib.freecartracker.ui.telemetry

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObdConnectionScreen(onBack: () -> Unit) {
    val vm = containerViewModel { c ->
        ObdConnectionViewModel(
            c.appContext,
            c.telemetrySettings,
            c.transportFactory,
            c.locationSourceFactory,
            c.obdCompanionManager,
            c.vehicleRepository,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val config = state.config

    val btPermissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.BLUETOOTH_CONNECT] == true) vm.refreshBondedDevices()
    }

    // CompanionDeviceManager hands back an IntentSender; the system dialog must be launched from
    // here (an Activity). On confirmation the manager's callback finalizes the toggle.
    val associationLauncher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) vm.onAssociationCancelled()
    }
    LaunchedEffect(state.pendingAssociation) {
        state.pendingAssociation?.let { sender ->
            vm.consumePendingAssociation()
            associationLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OBD connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Adapter type", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransportChip("WiFi", config.kind == ObdTransportKind.WIFI) {
                            vm.setKind(ObdTransportKind.WIFI)
                        }
                        TransportChip("Bluetooth", config.kind == ObdTransportKind.BT_CLASSIC) {
                            vm.setKind(ObdTransportKind.BT_CLASSIC)
                        }
                        TransportChip("BLE", config.kind == ObdTransportKind.BLE) {
                            vm.setKind(ObdTransportKind.BLE)
                        }
                    }
                }
            }

            when (config.kind) {
                ObdTransportKind.WIFI -> WifiCard(config.wifiHost, config.wifiPort, vm)
                ObdTransportKind.BT_CLASSIC, ObdTransportKind.BLE -> BluetoothCard(
                    state = state,
                    vm = vm,
                    onRequestPermission = {
                        btPermissionLauncher.launch(
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                        )
                    },
                )
                ObdTransportKind.NONE -> Unit
            }

            if (config.kind == ObdTransportKind.BLE) {
                BleAdvancedCard(config.bleServiceUuid, config.bleNotifyUuid, config.bleWriteUuid, vm)
            }

            AssociatedVehicleCard(
                vehicles = state.vehicles,
                selectedId = state.adapterVehicleId,
                onSelect = vm::setAdapterVehicle,
            )

            if (config.kind == ObdTransportKind.BT_CLASSIC) {
                AutoStartCard(state = state, onToggle = vm::setAutoStart)
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                        OutlinedButton(
                            onClick = vm::testConnection,
                            enabled = !state.testing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (state.testing) "Testing…" else "Test connection",
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    state.testResult?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                    if (state.savedAt > 0) {
                        Text(
                            "Saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun WifiCard(host: String, port: Int, vm: ObdConnectionViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WiFi adapter", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = host,
                onValueChange = vm::setWifiHost,
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port.toString(),
                onValueChange = vm::setWifiPort,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BluetoothCard(
    state: ObdConnUiState,
    vm: ObdConnectionViewModel,
    onRequestPermission: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paired devices", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pair your adapter in Android Bluetooth settings first, then pick it here.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh paired devices")
            }
            state.bonded.forEach { device ->
                val selected = device.address == state.config.deviceAddress
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, onClick = { vm.selectDevice(device) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { vm.selectDevice(device) })
                    Column {
                        Text(device.name, fontWeight = FontWeight.Medium)
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.bonded.isEmpty()) {
                Text("No paired devices loaded.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AutoStartCard(state: ObdConnUiState, onToggle: (Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-start recording", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Start a trip automatically when this adapter connects — even if the app is closed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.autoStartEnabled,
                    onCheckedChange = onToggle,
                    enabled = state.config.deviceAddress != null,
                )
            }
            if (state.config.deviceAddress == null) {
                Text(
                    "Pick a paired adapter above first.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.autoStartError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AssociatedVehicleCard(
    vehicles: List<me.crsib.freecartracker.data.entity.Vehicle>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    if (vehicles.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Associated vehicle", style = MaterialTheme.typography.titleMedium)
            Text(
                "Trips this adapter auto-starts are recorded for this car. " +
                    "Keep \"Selected vehicle\" to use whichever car is currently active.",
                style = MaterialTheme.typography.bodySmall,
            )
            VehicleOption("Selected vehicle", selected = selectedId == null) { onSelect(null) }
            vehicles.forEach { v ->
                VehicleOption(v.name, selected = selectedId == v.id) { onSelect(v.id) }
            }
        }
    }
}

@Composable
private fun VehicleOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(name)
    }
}

@Composable
private fun BleAdvancedCard(service: String, notify: String, write: String, vm: ObdConnectionViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("BLE service UUIDs (advanced)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Defaults cover common FFF0 clones. Change only if your adapter differs.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(service, vm::setBleService, label = { Text("Service UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notify, vm::setBleNotify, label = { Text("Notify UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(write, vm::setBleWrite, label = { Text("Write UUID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

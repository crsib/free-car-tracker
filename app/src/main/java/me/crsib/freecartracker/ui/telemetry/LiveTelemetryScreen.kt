package me.crsib.freecartracker.ui.telemetry

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.telemetry.obd.pid.ExtendedKeys
import me.crsib.freecartracker.telemetry.service.RecordingState
import me.crsib.freecartracker.telemetry.service.TelemetryService
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.formatDuration
import me.crsib.freecartracker.ui.util.formatNumber
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTelemetryScreen(
    onBack: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val context = LocalContext.current
    val vm = containerViewModel { c -> LiveTelemetryViewModel(c.tripRecorder, c.vehicleSelection) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val live = state.live
    val recording = state.recordingState != RecordingState.IDLE

    val vehicleId = state.selectedVehicle?.id

    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        // Start once location (the minimum) is granted; OBD works without BT for GPS-only trips.
        if (vehicleId != null && grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            TelemetryService.start(context, vehicleId)
        }
    }

    fun onStartStop() {
        if (recording) {
            TelemetryService.stop(context)
        } else if (vehicleId != null) {
            permissionLauncher.launch(requiredPermissions(context))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenConnection) {
                        Icon(Icons.Filled.Settings, contentDescription = "OBD connection")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusRow(state.recordingState, live.transport, live.obdConnected, live.gpsFix)

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Gauge(
                    label = "Speed",
                    value = live.speedKmh ?: live.gpsSpeedKmh,
                    max = 200.0,
                    unit = "km/h",
                    modifier = Modifier.weight(1f),
                )
                Gauge(
                    label = "RPM",
                    value = live.rpm,
                    max = 8000.0,
                    unit = "rpm",
                    modifier = Modifier.weight(1f),
                )
            }

            val showEv = state.selectedVehicle?.powertrain?.usesCharging == true
            val tiles = buildTiles(live, showEv)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tiles) { tile -> MetricTile(tile) }
            }

            Button(
                onClick = ::onStartStop,
                enabled = vehicleId != null,
                modifier = Modifier.fillMaxWidth(),
                colors = if (recording) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    when {
                        vehicleId == null -> "Add a vehicle first"
                        recording -> "Stop recording"
                        else -> "Start recording"
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    state: RecordingState,
    transport: ObdTransportKind,
    obdConnected: Boolean,
    gpsFix: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = {}, label = { Text(state.label()) }, colors = AssistChipDefaults.assistChipColors())
        if (transport != ObdTransportKind.NONE) {
            AssistChip(
                onClick = {},
                label = { Text(if (obdConnected) "OBD: ${transport.name}" else "OBD: …") },
            )
        }
        AssistChip(onClick = {}, label = { Text(if (gpsFix) "GPS ✓" else "GPS …") })
    }
}

@Composable
private fun Gauge(label: String, value: Double?, max: Double, unit: String, modifier: Modifier = Modifier) {
    val arcColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    ElevatedCard(modifier) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1.3f).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val fraction = ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat()
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = size.minDimension * 0.10f)
                val sweepTotal = 270f
                val startAngle = 135f
                val diameter = min(size.width, size.height) - stroke.width
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                drawArc(trackColor, startAngle, sweepTotal, false, topLeft, arcSize, style = stroke)
                drawArc(arcColor, startAngle, sweepTotal * fraction, false, topLeft, arcSize, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value?.let { formatNumber(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(unit, style = MaterialTheme.typography.labelSmall)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class Tile(val label: String, val value: String)

@Composable
private fun MetricTile(tile: Tile) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(tile.label, style = MaterialTheme.typography.labelMedium)
            Text(tile.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun buildTiles(live: me.crsib.freecartracker.telemetry.service.LiveTelemetry, showEv: Boolean): List<Tile> =
    buildList {
        add(Tile("Throttle", live.throttlePct.pct()))
        add(Tile("Engine load", live.engineLoadPct.pct()))
        add(Tile("Coolant", live.coolantTempC.temp()))
        add(Tile("Battery (12V)", live.controlModuleVoltage?.let { "${formatNumber(it)} V" } ?: "—"))
        add(Tile("Fuel level", live.fuelLevelPct.pct()))
        add(Tile("Distance", "${formatNumber(live.distanceKm)} km"))
        add(Tile("Elapsed", formatDuration(live.elapsedSec)))
        if (showEv) {
            add(Tile("Hybrid batt.", live.hybridBatteryLifePct.pct()))
            live.extended[ExtendedKeys.EV_SOC]?.let { add(Tile("EV charge", "${formatNumber(it)} %")) }
            live.extended[ExtendedKeys.MOTOR_TEMP]?.let { add(Tile("Motor temp", "${formatNumber(it)} °C")) }
        }
    }

private fun Double?.pct(): String = this?.let { "${formatNumber(it)} %" } ?: "—"
private fun Double?.temp(): String = this?.let { "${formatNumber(it)} °C" } ?: "—"

private fun RecordingState.label(): String = when (this) {
    RecordingState.IDLE -> "Idle"
    RecordingState.CONNECTING -> "Connecting…"
    RecordingState.RECORDING -> "Recording"
    RecordingState.RECONNECTING -> "Reconnecting…"
    RecordingState.ERROR -> "Error"
}

private fun requiredPermissions(context: android.content.Context): Array<String> {
    val config = (context.applicationContext as me.crsib.freecartracker.CarTrackerApp)
        .container.telemetrySettings.loadConnection()
    return buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (config.kind == ObdTransportKind.BT_CLASSIC || config.kind == ObdTransportKind.BLE) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }.toTypedArray()
}

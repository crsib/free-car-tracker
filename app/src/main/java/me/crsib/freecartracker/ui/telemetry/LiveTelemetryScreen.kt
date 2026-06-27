package me.crsib.freecartracker.ui.telemetry

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusRow(
    state: RecordingState,
    transport: ObdTransportKind,
    obdConnected: Boolean,
    gpsFix: Boolean,
) {
    // Compact icon pills: state is encoded by icon + colour + animation (a label is kept too, so
    // the meaning never relies on colour alone). FlowRow lets a pill wrap as a whole unit on narrow
    // screens instead of squeezing one pill's content.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val rec = recordingVisual(state)
        StatusPill(icon = rec.icon, tint = rec.tint, label = rec.label, pulsing = rec.pulsing, spinning = rec.spinning)

        if (transport != ObdTransportKind.NONE) {
            val obdIcon = when (transport) {
                ObdTransportKind.WIFI -> Icons.Filled.Wifi
                else -> Icons.Filled.Bluetooth
            }
            StatusPill(
                icon = obdIcon,
                tint = if (obdConnected) StatusGreen else StatusAmber,
                label = transport.shortLabel(),
                pulsing = !obdConnected,
            )
        }

        StatusPill(
            icon = if (gpsFix) Icons.Filled.LocationOn else Icons.Filled.LocationSearching,
            tint = if (gpsFix) StatusGreen else StatusAmber,
            label = "GPS",
            pulsing = !gpsFix,
        )
    }
}

/** Status-semantic colours (Material3 has no built-in success/warning role). */
private val StatusGreen = Color(0xFF4CAF50)
private val StatusAmber = Color(0xFFFFB300)
private val StatusRed = Color(0xFFE53935)

private data class RecordingVisual(
    val icon: ImageVector,
    val tint: Color,
    val label: String,
    val pulsing: Boolean = false,
    val spinning: Boolean = false,
)

@Composable
private fun recordingVisual(state: RecordingState): RecordingVisual = when (state) {
    RecordingState.IDLE ->
        RecordingVisual(Icons.Filled.FiberManualRecord, MaterialTheme.colorScheme.onSurfaceVariant, "Idle")
    RecordingState.CONNECTING ->
        RecordingVisual(Icons.Filled.Autorenew, StatusAmber, "Connecting", spinning = true)
    RecordingState.RECORDING ->
        RecordingVisual(Icons.Filled.FiberManualRecord, StatusRed, "REC", pulsing = true)
    RecordingState.RECONNECTING ->
        RecordingVisual(Icons.Filled.Autorenew, StatusAmber, "Reconnecting", spinning = true)
    RecordingState.ERROR ->
        RecordingVisual(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, "Error")
}

private fun ObdTransportKind.shortLabel(): String = when (this) {
    ObdTransportKind.BT_CLASSIC -> "BT"
    ObdTransportKind.BLE -> "BLE"
    ObdTransportKind.WIFI -> "Wi-Fi"
    ObdTransportKind.NONE -> "OBD"
}

/**
 * A small rounded status indicator: a colour-tinted icon plus a one-line label. [pulsing] fades
 * the icon in and out (live/searching states); [spinning] rotates it (connecting). The label never
 * wraps so the pill sizes to one line.
 */
@Composable
private fun StatusPill(
    icon: ImageVector,
    tint: Color,
    label: String,
    pulsing: Boolean = false,
    spinning: Boolean = false,
) {
    // Run both animations unconditionally (Compose requires stable hook calls) and apply each only
    // when its flag is set, so a pill switching state — e.g. IDLE → RECORDING — stays valid.
    val transition = rememberInfiniteTransition(label = "statusPill")
    val pulseAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    val spinDeg by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "spin",
    )
    val alpha = if (pulsing) pulseAlpha else 1f
    val rotation = if (spinning) spinDeg else 0f

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { this.alpha = alpha; rotationZ = rotation },
            )
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun Gauge(label: String, value: Double?, max: Double, unit: String, modifier: Modifier = Modifier) {
    val arcColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    ElevatedCard(modifier) {
        BoxWithConstraints(
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
            // Keep the readout inside the arc: the gauge height is the limiting
            // dimension, and text must fit the circle inscribed in it (~0.6×).
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = maxHeight * 0.6f),
            ) {
                AutoSizeText(
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

/**
 * Single-line text that shrinks its font until it fits the available width.
 * Replaces Compose 1.8's [autoSize], which the current BOM predates.
 */
@Composable
private fun AutoSizeText(
    text: String,
    style: TextStyle,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    var scaledStyle by remember(text, style) { mutableStateOf(style) }
    var readyToDraw by remember(text, style) { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.drawWithContent { if (readyToDraw) drawContent() },
        style = scaledStyle,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { result ->
            if (result.didOverflowWidth) {
                scaledStyle = scaledStyle.copy(fontSize = scaledStyle.fontSize * 0.9f)
            } else {
                readyToDraw = true
            }
        },
    )
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
        live.odometerKm?.let { add(Tile("Odometer", "${formatNumber(it)} km")) }
        if (showEv) {
            add(Tile("Hybrid batt.", live.hybridBatteryLifePct.pct()))
            live.hvBatteryPowerKw?.let { add(Tile("HV power", "${formatNumber(it)} kW")) }
            live.hvBatteryVoltage?.let { add(Tile("HV voltage", "${formatNumber(it)} V")) }
            live.hvBatteryCurrent?.let { add(Tile("HV current", "${formatNumber(it)} A")) }
            live.extended[ExtendedKeys.EV_SOC]?.let { add(Tile("EV charge", "${formatNumber(it)} %")) }
            live.extended[ExtendedKeys.MOTOR_TEMP]?.let { add(Tile("Motor temp", "${formatNumber(it)} °C")) }
        }
    }

private fun Double?.pct(): String = this?.let { "${formatNumber(it)} %" } ?: "—"
private fun Double?.temp(): String = this?.let { "${formatNumber(it)} °C" } ?: "—"

private fun requiredPermissions(context: android.content.Context): Array<String> {
    val config = (context.applicationContext as me.crsib.freecartracker.CarTrackerApp)
        .container.telemetrySettings.loadConnection()
    return buildList {
        // COARSE must accompany FINE: on Android 12 a fine-only request is silently ignored
        // (no permission dialog shown) unless COARSE is requested in the same call.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (config.kind == ObdTransportKind.BT_CLASSIC || config.kind == ObdTransportKind.BLE) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }.toTypedArray()
}

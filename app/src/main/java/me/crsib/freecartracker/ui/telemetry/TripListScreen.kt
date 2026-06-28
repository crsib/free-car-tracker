package me.crsib.freecartracker.ui.telemetry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.entity.Trip
import me.crsib.freecartracker.ui.components.VehicleSwitcher
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.formatDate
import me.crsib.freecartracker.ui.util.formatDuration
import me.crsib.freecartracker.ui.util.formatNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripListScreen(
    onRecord: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    onOpenConnection: () -> Unit,
) {
    val vm = containerViewModel { c -> TripListViewModel(c.vehicleSelection, c.telemetryRepository) }
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val vehicle = state.vehicle
                    if (vehicle != null) {
                        VehicleSwitcher(
                            vehicles = state.vehicles,
                            selected = vehicle,
                            onSelect = vm::selectVehicle,
                        )
                    } else {
                        Text("Trips")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenConnection) {
                        Icon(Icons.Filled.Settings, contentDescription = "OBD connection")
                    }
                },
            )
        },
        floatingActionButton = {
            val hasActiveTrip = state.trips.any { it.status == TripStatus.RECORDING }
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(Icons.Filled.Sensors, contentDescription = null) },
                text = { Text(if (hasActiveTrip) "Live" else "Record") },
            )
        },
    ) { padding ->
        if (state.trips.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (state.vehicle == null) "No vehicle selected." else "No trips yet.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.vehicle != null) {
                    Text(
                        "Tap Record to capture a drive.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.trips, key = { it.id }) { trip ->
                TripRow(
                    trip = trip,
                    finalOdometerKm = state.odometerByTrip[trip.id],
                    avgFuelUsageL100 = state.avgFuelUsageByTrip[trip.id],
                    onClick = { onOpenTrip(trip.id) },
                    onDelete = { vm.deleteTrip(trip.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripRow(
    trip: Trip,
    finalOdometerKm: Double?,
    avgFuelUsageL100: Double?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(formatDate(trip.startedAtMillis), fontWeight = FontWeight.Medium)
                val hasStats = trip.distanceKm != null || trip.durationSec != null ||
                    trip.avgSpeedKmh != null || avgFuelUsageL100 != null
                if (hasStats) {
                    // Each stat is an atomic icon+value, so FlowRow wraps whole stats to the next
                    // line instead of breaking a value mid-token (e.g. "8.8 L/" / "100km").
                    FlowRow(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        trip.distanceKm?.let { SummaryStat(Icons.Filled.Route, "${formatNumber(it)} km") }
                        trip.durationSec?.let { SummaryStat(Icons.Filled.Schedule, formatDuration(it)) }
                        trip.avgSpeedKmh?.let { SummaryStat(Icons.Filled.Speed, "${formatNumber(it)} km/h") }
                        avgFuelUsageL100?.let { SummaryStat(Icons.Filled.LocalGasStation, "${formatNumber(it)} L/100km") }
                    }
                } else {
                    Text(
                        "No summary",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                finalOdometerKm?.let {
                    SummaryStat(
                        icon = Icons.Filled.Straighten,
                        text = "${formatNumber(it, 0)} km",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (trip.status == TripStatus.ABORTED) {
                    Text(
                        "Ended unexpectedly",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (trip.status == TripStatus.RECORDING) {
                    Text(
                        "Recording…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onClick() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** One trip statistic: a small icon and its value, kept on a single line so it never breaks mid-value. */
@Composable
private fun SummaryStat(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

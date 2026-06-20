package me.crsib.freecartracker.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.ui.components.Sparkline
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.costBucketLabel
import me.crsib.freecartracker.ui.util.formatDate
import me.crsib.freecartracker.ui.util.formatMoney
import me.crsib.freecartracker.ui.util.formatNumber
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAddEntry: (Long) -> Unit,
    onEditEntry: (Long, EntryKind, Long) -> Unit,
    onAddVehicle: () -> Unit,
) {
    val vm = containerViewModel { c ->
        DashboardViewModel(c.vehicleSelection, c.entryRepository, c.preferences)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.selected != null) {
                        VehicleSwitcher(
                            vehicles = state.vehicles,
                            selected = state.selected!!,
                            onSelect = vm::selectVehicle,
                        )
                    } else {
                        Text("Dashboard")
                    }
                },
            )
        },
        floatingActionButton = {
            state.selected?.let { v ->
                FloatingActionButton(onClick = { onAddEntry(v.id) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add entry")
                }
            }
        },
    ) { padding ->
        val vehicle = state.selected
        if (vehicle == null) {
            EmptyDashboard(
                loading = state.loading,
                onAddVehicle = onAddVehicle,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        val system = vehicle.unitSystem
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    KpiCard(
                        title = "Fuel economy",
                        value = UnitConversions.fuelEconomyToDisplay(state.latestFuelEconomyL100, system)
                            ?.let { formatNumber(it, 1) } ?: "—",
                        unit = UnitConversions.fuelEconomyUnit(system),
                        modifier = Modifier.weight(1f),
                    )
                    if (vehicle.powertrain.usesCharging) {
                        KpiCard(
                            title = "Electric",
                            value = UnitConversions.electricEconomyToDisplay(state.electricEconomyKwh100, system)
                                ?.let { formatNumber(it, 1) } ?: "—",
                            unit = UnitConversions.electricEconomyUnit(system),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    KpiCard(
                        title = "Spent (${state.windowDays}d)",
                        value = formatMoney(state.totalSpendMinor, vehicle.currency),
                        unit = "",
                        modifier = Modifier.weight(1f),
                    )
                    KpiCard(
                        title = "Cost / ${UnitConversions.distanceUnit(system)}",
                        value = costPerDistanceLabel(state.costPerKmMinor, vehicle, system),
                        unit = "",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state.trend.size >= 2) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Consumption trend (${UnitConversions.fuelEconomyUnit(system)})",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Sparkline(
                                values = state.trend.map {
                                    (UnitConversions.fuelEconomyToDisplay(it.litresPer100Km, system) ?: 0.0).toFloat()
                                },
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }

            if (state.buckets.isNotEmpty()) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Spending by category", style = MaterialTheme.typography.labelLarge)
                            val maxBucket = state.buckets.maxOf { it.totalMinor }.coerceAtLeast(1)
                            state.buckets.forEach { bucket ->
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(costBucketLabel(bucket.bucket), modifier = Modifier.weight(1f))
                                    Text(formatMoney(bucket.totalMinor, vehicle.currency))
                                }
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { bucket.totalMinor.toFloat() / maxBucket },
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text("Recent entries", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (state.recent.isEmpty()) {
                item { Text("No entries yet. Tap + to log fuel, charging or an expense.") }
            } else {
                items(state.recent.size) { idx ->
                    val item = state.recent[idx]
                    RecentRow(
                        item = item,
                        onClick = { onEditEntry(vehicle.id, item.kind, item.id) },
                    )
                }
            }
        }
    }
}

private fun costPerDistanceLabel(
    costPerKmMinor: Double?,
    vehicle: Vehicle,
    system: me.crsib.freecartracker.domain.model.UnitSystem,
): String {
    if (costPerKmMinor == null) return "—"
    val perDisplay = if (system == me.crsib.freecartracker.domain.model.UnitSystem.IMPERIAL) {
        costPerKmMinor * UnitConversions.KM_PER_MILE
    } else {
        costPerKmMinor
    }
    return formatMoney(perDisplay.roundToLong(), vehicle.currency)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleSwitcher(
    vehicles: List<Vehicle>,
    selected: Vehicle,
    onSelect: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = true }) {
            Text(selected.name, style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch vehicle")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vehicles.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.name) },
                    onClick = { open = false; onSelect(v.id) },
                )
            }
        }
    }
}

@Composable
private fun KpiCard(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(costBucketLabel(item.title), fontWeight = FontWeight.Medium)
                Text(formatDate(item.dateMillis), style = MaterialTheme.typography.bodySmall)
            }
            Text(formatMoney(item.amountMinor, item.currency), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyDashboard(loading: Boolean, onAddVehicle: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            Text("Loading…")
        } else {
            Text("Welcome to Car Tracker", style = MaterialTheme.typography.titleLarge)
            Text(
                "Add your first vehicle to start tracking fuel, charging and costs.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            androidx.compose.material3.Button(onClick = onAddVehicle) { Text("Add vehicle") }
        }
    }
}

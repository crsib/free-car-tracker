package me.crsib.freecartracker.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.ui.components.ChartPoint
import me.crsib.freecartracker.ui.components.TripChart
import me.crsib.freecartracker.ui.components.VehicleSwitcher
import me.crsib.freecartracker.ui.components.rememberChartSync
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.costBucketLabel
import me.crsib.freecartracker.ui.util.formatDate
import me.crsib.freecartracker.ui.util.formatMoney
import me.crsib.freecartracker.ui.util.formatNumber
import me.crsib.freecartracker.ui.util.icon
import me.crsib.freecartracker.ui.util.windowLabel
import me.crsib.freecartracker.ui.util.windowMenuLabel
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
                actions = {
                    if (state.selected != null) {
                        WindowSelector(
                            windowDays = state.windowDays,
                            onSelect = vm::setWindowDays,
                        )
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
                        title = "Spent (${windowLabel(state.windowDays)})",
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

            item {
                PeriodStatsCard(state = state, vehicle = vehicle)
            }

            val trendPoints = state.trend
                .mapNotNull { tp ->
                    UnitConversions.fuelEconomyToDisplay(tp.litresPer100Km, system)
                        ?.let { ChartPoint(tp.dateMillis, it.toFloat()) }
                }
                .sortedBy { it.timeMillis }
            if (trendPoints.size >= 2) {
                item {
                    val trendSync = rememberChartSync(trendPoints)
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Consumption trend (${UnitConversions.fuelEconomyUnit(system)})",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "Pinch to zoom · drag to read values",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TripChart(
                                points = trendPoints,
                                sync = trendSync,
                                domainStartMillis = trendPoints.first().timeMillis,
                                domainEndMillis = trendPoints.last().timeMillis,
                                unit = UnitConversions.fuelEconomyUnit(system),
                                timeLabel = { formatDate(it) },
                                averageLine = true,
                                trendLine = true,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                LegendItem(Color(0xFF1565C0), "Consumption")
                                LegendItem(MaterialTheme.colorScheme.onSurfaceVariant, "Average")
                                LegendItem(Color(0xFFEF6C00), "Trend")
                            }
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

@Composable
private fun WindowSelector(windowDays: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = true }) {
            Text(windowLabel(windowDays))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change period")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppPreferences.WINDOW_OPTIONS.forEach { days ->
                DropdownMenuItem(
                    text = { Text(windowMenuLabel(days)) },
                    onClick = { open = false; onSelect(days) },
                )
            }
        }
    }
}

@Composable
private fun PeriodStatsCard(state: DashboardUiState, vehicle: Vehicle) {
    val system = vehicle.unitSystem

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Period statistics (${windowLabel(state.windowDays)})",
                style = MaterialTheme.typography.labelLarge,
            )
            StatRow(
                "Distance travelled",
                state.windowDistanceKm
                    ?.let { "${formatNumber(UnitConversions.kmToDisplay(it, system), 0)} ${UnitConversions.distanceUnit(system)}" }
                    ?: "—",
            )

            if (vehicle.powertrain.usesFuel) {
                val economies = state.windowSegmentEconomiesL100
                    .mapNotNull { UnitConversions.fuelEconomyToDisplay(it, system) }
                val ecoUnit = UnitConversions.fuelEconomyUnit(system)
                StatSectionHeader(EntryKind.FUEL.icon(), "Fuel")
                StatRow(
                    "Used (total)",
                    state.windowFuelVolumeL.takeIf { it > 0.0 }
                        ?.let { "${formatNumber(UnitConversions.litresToDisplay(it, system), 1)} ${UnitConversions.volumeUnit(system)}" }
                        ?: "—",
                )
                StatRow(
                    "Consumption (avg)",
                    economies.takeIf { it.isNotEmpty() }
                        ?.let { "${formatNumber(it.average(), 1)} $ecoUnit" } ?: "—",
                )
                StatRow(
                    "Consumption (min / max)",
                    if (economies.isEmpty()) "—"
                    else "${formatNumber(economies.min(), 1)} / ${formatNumber(economies.max(), 1)} $ecoUnit",
                )
            }

            if (vehicle.powertrain == Powertrain.REV) {
                val electric = state.windowSegmentElectricKwh100
                    .mapNotNull { UnitConversions.electricEconomyToDisplay(it, system) }
                val elUnit = UnitConversions.electricEconomyUnit(system)
                StatSectionHeader(EntryKind.CHARGE.icon(), "Electricity")
                StatRow(
                    "Used (total)",
                    state.windowEnergyKwh.takeIf { it > 0.0 }
                        ?.let { "${formatNumber(it, 1)} kWh" } ?: "—",
                )
                StatRow(
                    "Consumption (avg)",
                    electric.takeIf { it.isNotEmpty() }
                        ?.let { "${formatNumber(it.average(), 1)} $elUnit" } ?: "—",
                )
                StatRow(
                    "Consumption (min / max)",
                    if (electric.isEmpty()) "—"
                    else "${formatNumber(electric.min(), 1)} / ${formatNumber(electric.max(), 1)} $elUnit",
                )
            }
        }
    }
}

@Composable
private fun StatSectionHeader(icon: ImageVector, title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(16.dp).height(3.dp).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KpiCard(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            // Shrink-to-fit: a long currency value (e.g. "RUB223,659.21") otherwise character-wraps
            // mid-number on the narrow half-width card. Compose 1.7 has no built-in text autosize,
            // so step the font down on overflow until it fits one line (floor keeps it legible).
            val baseSize = MaterialTheme.typography.headlineSmall.fontSize
            var fontSize by remember(value) { mutableStateOf(baseSize) }
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { result ->
                    if (result.didOverflowWidth && fontSize.value > 14f) {
                        fontSize = (fontSize.value * 0.92f).sp
                    }
                },
            )
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                item.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
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

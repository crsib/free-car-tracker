package me.crsib.freecartracker.ui.telemetry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.telemetry.data.TripData
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import me.crsib.freecartracker.telemetry.obd.pid.ExtendedKeys
import me.crsib.freecartracker.ui.components.ChartPoint
import me.crsib.freecartracker.ui.components.ChartSync
import me.crsib.freecartracker.ui.components.TripChart
import me.crsib.freecartracker.ui.components.rememberChartSync
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.formatDate
import me.crsib.freecartracker.ui.util.formatDuration
import me.crsib.freecartracker.ui.util.formatNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit, onOpenMap: (Long) -> Unit) {
    val vm = containerViewModel(key = "trip-$tripId") { c ->
        TripDetailViewModel(c.telemetryRepository, tripId)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.delete(onBack) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete trip")
                    }
                },
            )
        },
    ) { padding ->
        val data = state.data
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            data == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("Trip not found.")
            }
            else -> TripDetailContent(data, onOpenMap, Modifier.padding(padding))
        }
    }
}

@Composable
private fun TripDetailContent(data: TripData, onOpenMap: (Long) -> Unit, modifier: Modifier = Modifier) {
    val trip = data.trip
    val samples = data.obdSamples

    // One shared viewport so the scrub cursor and the zoom/pan window move together across charts.
    val sync = rememberChartSync(data)
    val domainStart = samples.firstOrNull()?.timestampMillis ?: 0L
    val domainEnd = samples.lastOrNull()?.timestampMillis ?: 0L

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(formatDate(trip.startedAtMillis), style = MaterialTheme.typography.titleMedium)
                SummaryTable(data)
            }
        }

        ChartCard("Speed (km/h)", "km/h", series(samples) { it.speedKmh }, sync, domainStart, domainEnd)
        ChartCard("RPM", "rpm", series(samples) { it.rpm }, sync, domainStart, domainEnd, decimals = 0)
        ChartCard("Coolant temp (°C)", "°C", series(samples) { it.coolantTempC }, sync, domainStart, domainEnd)
        // Fuel usage for fuel-burning cars. Shows only when the adapter reports fuel rate
        // (PID 0x5E); it's the ICE analog to the HV-battery-power chart below.
        ChartCard("Fuel rate (L/h)", "L/h", series(samples) { it.fuelRateLph }, sync, domainStart, domainEnd)
        ChartCard("Consumption (L/100km)", "L/100km", series(samples) { it.derivedL100 }, sync, domainStart, domainEnd)
        ChartCard("HV battery power (kW)", "kW", series(samples) { it.hvBatteryPowerKw }, sync, domainStart, domainEnd)

        if (data.dtcs.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Trouble codes", style = MaterialTheme.typography.titleMedium)
                    data.dtcs.forEach { dtc ->
                        Text(
                            "${dtc.code}${if (dtc.pending) " (pending)" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        if (data.gpsPoints.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Route", style = MaterialTheme.typography.titleMedium)
                    RouteMap(
                        points = data.gpsPoints,
                        interactive = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(top = 8.dp)
                            // osmdroid's MapView draws past its Compose bounds (Compose doesn't
                            // clip children by default), so without this the map overflows onto
                            // the "Route" title and the "Open full map" button below it.
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    OutlinedButton(
                        onClick = { onOpenMap(trip.id) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Open full map") }
                }
            }
        }
    }
}

/** The trip stats as a grouped key/value table. */
@Composable
private fun SummaryTable(data: TripData) {
    val trip = data.trip

    val fuelLevel = firstLast(data.obdSamples) { it.fuelLevelPct }
    val batteryLevel = batteryStartEnd(data)
    // Prefer the fuel-rate integral; fall back to tank-level drop × capacity so the figure shows
    // even on cars (like most EVs/REVs) that don't report fuel rate.
    val tankDelta = tankDeltaLitres(fuelLevel, data.vehicle?.tankCapacityL)
    val fuelUsedL = trip.fuelUsedL ?: tankDelta
    val fuelUsedEstimated = trip.fuelUsedL == null && tankDelta != null

    SummarySection(
        "Distance & time",
        buildList {
            trip.distanceKm?.let { add("Distance" to "${formatNumber(it)} km") }
            trip.durationSec?.let { add("Duration" to formatDuration(it)) }
            trip.movingSec?.let { add("Moving" to formatDuration(it)) }
        },
    )
    SummarySection(
        "Speed",
        buildList {
            trip.avgSpeedKmh?.let { add("Average" to "${formatNumber(it)} km/h") }
            trip.maxSpeedKmh?.let { add("Maximum" to "${formatNumber(it)} km/h") }
        },
    )
    SummarySection(
        "Energy & fuel",
        buildList {
            fuelUsedL?.let { add("Fuel used" to "${formatNumber(it)} L${if (fuelUsedEstimated) " (est.)" else ""}") }
            trip.energyUsedKwh?.let { add("Energy used" to "${formatNumber(it)} kWh") }
            trip.avgConsumptionL100?.let { add("Avg consumption" to "${formatNumber(it)} L/100km") }
        },
    )
    SummarySection(
        "Levels (start → end)",
        buildList {
            fuelLevel?.let { add("Fuel tank" to levelRange(it)) }
            batteryLevel?.let { add("Battery" to levelRange(it)) }
        },
    )
    SummarySection(
        "Data",
        listOf(
            "OBD samples" to trip.obdSampleCount.toString(),
            "GPS points" to trip.gpsPointCount.toString(),
        ),
    )
}

@Composable
private fun SummarySection(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        rows.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    unit: String,
    points: List<ChartPoint>,
    sync: ChartSync,
    domainStartMillis: Long,
    domainEndMillis: Long,
    decimals: Int = 1,
) {
    if (points.size < 2) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                "Pinch to zoom · drag to read — synced across charts",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TripChart(
                points = points,
                sync = sync,
                domainStartMillis = domainStartMillis,
                domainEndMillis = domainEndMillis,
                unit = unit,
                valueFormat = { formatNumber(it.toDouble(), decimals) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun series(samples: List<ObdSample>, selector: (ObdSample) -> Double?): List<ChartPoint> =
    samples.mapNotNull { s -> selector(s)?.let { ChartPoint(s.timestampMillis, it.toFloat()) } }

/** First and last non-null reading of a metric across the (time-ordered) samples. */
private fun firstLast(samples: List<ObdSample>, selector: (ObdSample) -> Double?): Pair<Double, Double>? {
    val values = samples.mapNotNull(selector)
    return if (values.isEmpty()) null else values.first() to values.last()
}

/** Battery charge start/end: EV state-of-charge (Mode 22) if present, else hybrid battery % (0x5B). */
private fun batteryStartEnd(data: TripData): Pair<Double, Double>? {
    val soc = data.extendedSamples.filter { it.metricKey == ExtendedKeys.EV_SOC }
    if (soc.isNotEmpty()) return soc.first().value to soc.last().value
    return firstLast(data.obdSamples) { it.hybridBatteryLifePct }
}

/** Litres burned inferred from the tank-level drop, or null if it didn't drop / capacity unknown. */
private fun tankDeltaLitres(level: Pair<Double, Double>?, capacityL: Double?): Double? {
    if (level == null || capacityL == null) return null
    val used = (level.first - level.second) / 100.0 * capacityL
    return used.takeIf { it > 0.0 }
}

private fun levelRange(level: Pair<Double, Double>): String =
    "${formatNumber(level.first)}% → ${formatNumber(level.second)}%"

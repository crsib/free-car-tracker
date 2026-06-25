package me.crsib.freecartracker.ui.telemetry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.telemetry.data.TripData
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import me.crsib.freecartracker.ui.components.Sparkline
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripDetailContent(data: TripData, onOpenMap: (Long) -> Unit, modifier: Modifier = Modifier) {
    val trip = data.trip
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
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    trip.distanceKm?.let { Stat("Distance", "${formatNumber(it)} km") }
                    trip.durationSec?.let { Stat("Duration", formatDuration(it)) }
                    trip.movingSec?.let { Stat("Moving", formatDuration(it)) }
                    trip.avgSpeedKmh?.let { Stat("Avg speed", "${formatNumber(it)} km/h") }
                    trip.maxSpeedKmh?.let { Stat("Max speed", "${formatNumber(it)} km/h") }
                    trip.fuelUsedL?.let { Stat("Fuel used", "${formatNumber(it)} L") }
                    trip.energyUsedKwh?.let { Stat("Energy used", "${formatNumber(it)} kWh") }
                    trip.avgConsumptionL100?.let { Stat("Consumption", "${formatNumber(it)} L/100km") }
                    Stat("OBD samples", trip.obdSampleCount.toString())
                    Stat("GPS points", trip.gpsPointCount.toString())
                }
            }
        }

        ChartCard("Speed (km/h)", series(data.obdSamples) { it.speedKmh })
        ChartCard("RPM", series(data.obdSamples) { it.rpm })
        ChartCard("Consumption (L/100km)", series(data.obdSamples) { it.derivedL100 })
        ChartCard("HV battery power (kW)", series(data.obdSamples) { it.hvBatteryPowerKw })

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
                            .padding(top = 8.dp),
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

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.width(140.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChartCard(title: String, values: List<Float>) {
    if (values.size < 2) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Sparkline(values = values, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun series(samples: List<ObdSample>, selector: (ObdSample) -> Double?): List<Float> =
    samples.mapNotNull { selector(it)?.toFloat() }

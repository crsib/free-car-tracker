package me.crsib.freecartracker.ui.vehicles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleListScreen(
    onAddVehicle: () -> Unit,
    onEditVehicle: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm = containerViewModel { c -> VehicleListViewModel(c.vehicleRepository, c.preferences) }
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vehicles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddVehicle) {
                Icon(Icons.Filled.Add, contentDescription = "Add vehicle")
            }
        },
    ) { padding ->
        if (state.active.isEmpty() && state.archived.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No vehicles yet.", style = MaterialTheme.typography.titleMedium)
                Text("Tap + to add your first car.", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.active, key = { it.id }) { vehicle ->
                VehicleCard(
                    vehicle = vehicle,
                    isSelected = vehicle.id == state.selectedId,
                    onClick = { onEditVehicle(vehicle.id) },
                    onSetDefault = { vm.setSelected(vehicle) },
                    onArchive = { vm.setArchived(vehicle, true) },
                    onDelete = { vm.delete(vehicle) },
                )
            }
            if (state.archived.isNotEmpty()) {
                item {
                    Text(
                        "Archived",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(state.archived, key = { it.id }) { vehicle ->
                    VehicleCard(
                        vehicle = vehicle,
                        isSelected = false,
                        archived = true,
                        onClick = { onEditVehicle(vehicle.id) },
                        onSetDefault = { vm.setSelected(vehicle) },
                        onArchive = { vm.setArchived(vehicle, false) },
                        onDelete = { vm.delete(vehicle) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    isSelected: Boolean,
    archived: Boolean = false,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (isSelected) {
            CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.elevatedCardColors()
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vehicle.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (isSelected) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp).size(18.dp),
                        )
                    }
                }
                val subtitle = listOfNotNull(
                    vehicle.make,
                    vehicle.model,
                    vehicle.year?.toString(),
                ).joinToString(" ").ifBlank { vehicle.powertrain.label() }
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!archived && !isSelected) {
                    DropdownMenuItem(text = { Text("Set as default") }, onClick = {
                        menuOpen = false; onSetDefault()
                    })
                }
                DropdownMenuItem(
                    text = { Text(if (archived) "Unarchive" else "Archive") },
                    onClick = { menuOpen = false; onArchive() },
                )
                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                    menuOpen = false; onDelete()
                })
            }
        }
    }
}

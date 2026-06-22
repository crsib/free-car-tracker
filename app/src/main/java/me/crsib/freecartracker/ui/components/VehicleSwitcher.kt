package me.crsib.freecartracker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import me.crsib.freecartracker.data.entity.Vehicle

/**
 * Top-bar title showing the [selected] vehicle's name with a dropdown to switch between active
 * [vehicles]. Switching updates the app-wide selection, so it is shared across Dashboard, History
 * and Trips.
 */
@Composable
fun VehicleSwitcher(
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

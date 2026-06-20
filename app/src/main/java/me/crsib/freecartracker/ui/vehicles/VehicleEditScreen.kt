package me.crsib.freecartracker.ui.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.FuelType
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.UnitSystem
import me.crsib.freecartracker.ui.components.EnumDropdown
import me.crsib.freecartracker.ui.components.LabeledTextField
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(
    vehicleId: Long?,
    onDone: () -> Unit,
) {
    val vm = containerViewModel(key = "vehicle-edit-${vehicleId ?: "new"}") { c ->
        VehicleEditViewModel(c.vehicleRepository, c.preferences, vehicleId)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Add vehicle" else "Edit vehicle") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val dUnit = UnitConversions.distanceUnit(state.unitSystem)
            val vUnit = UnitConversions.volumeUnit(state.unitSystem)

            LabeledTextField(
                value = state.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = "Name *",
                isError = state.name.isBlank(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    value = state.make,
                    onValueChange = { v -> vm.update { it.copy(make = v) } },
                    label = "Make",
                    modifier = Modifier.weight(1f),
                )
                LabeledTextField(
                    value = state.model,
                    onValueChange = { v -> vm.update { it.copy(model = v) } },
                    label = "Model",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    value = state.year,
                    onValueChange = { v -> vm.update { it.copy(year = v.filter(Char::isDigit)) } },
                    label = "Year",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                LabeledTextField(
                    value = state.plate,
                    onValueChange = { v -> vm.update { it.copy(plate = v) } },
                    label = "Plate",
                    modifier = Modifier.weight(1f),
                )
            }

            EnumDropdown(
                label = "Powertrain",
                options = Powertrain.entries,
                selected = state.powertrain,
                onSelected = { p -> vm.update { it.copy(powertrain = p) } },
                labelOf = { it.label() },
            )

            if (state.powertrain.usesFuel) {
                EnumDropdown(
                    label = "Fuel type",
                    options = FuelType.entries,
                    selected = state.fuelType,
                    onSelected = { f -> vm.update { it.copy(fuelType = f) } },
                    labelOf = { it.label() },
                )
                LabeledTextField(
                    value = state.tankCapacity,
                    onValueChange = { v -> vm.update { it.copy(tankCapacity = v) } },
                    label = "Tank capacity",
                    keyboardType = KeyboardType.Decimal,
                    suffix = vUnit,
                )
            }
            if (state.powertrain.usesCharging) {
                LabeledTextField(
                    value = state.batteryCapacity,
                    onValueChange = { v -> vm.update { it.copy(batteryCapacity = v) } },
                    label = "Battery capacity",
                    keyboardType = KeyboardType.Decimal,
                    suffix = "kWh",
                )
            }

            EnumDropdown(
                label = "Units",
                options = UnitSystem.entries,
                selected = state.unitSystem,
                onSelected = { u -> vm.update { it.copy(unitSystem = u) } },
                labelOf = { it.label() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    value = state.currency,
                    onValueChange = { v -> vm.update { it.copy(currency = v.uppercase().take(3)) } },
                    label = "Currency",
                    isError = state.currency.length != 3,
                    modifier = Modifier.weight(1f),
                )
                LabeledTextField(
                    value = state.initialOdometer,
                    onValueChange = { v -> vm.update { it.copy(initialOdometer = v) } },
                    label = "Start odometer",
                    keyboardType = KeyboardType.Decimal,
                    suffix = dUnit,
                    modifier = Modifier.weight(1f),
                )
            }

            Button(
                onClick = { vm.save() },
                enabled = state.isValid,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(if (vm.isNew) "Add vehicle" else "Save changes")
            }
        }
    }
}

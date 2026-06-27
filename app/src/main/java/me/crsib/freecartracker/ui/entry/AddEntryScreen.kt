package me.crsib.freecartracker.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.ChargeType
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.domain.model.ExpenseCategory
import me.crsib.freecartracker.domain.model.FuelType
import me.crsib.freecartracker.domain.model.Recurrence
import me.crsib.freecartracker.ui.components.DatePickerField
import me.crsib.freecartracker.ui.components.EnumDropdown
import me.crsib.freecartracker.ui.components.OptionalDatePickerField
import me.crsib.freecartracker.ui.components.LabeledTextField
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.label

private fun EntryKind.label() = when (this) {
    EntryKind.FUEL -> "Fuel"
    EntryKind.CHARGE -> "Charge"
    EntryKind.EXPENSE -> "Expense"
}

private fun formatOdometer(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%,.0f".format(value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(
    vehicleId: Long,
    editKind: EntryKind?,
    editId: Long?,
    onDone: () -> Unit,
) {
    val vm = containerViewModel(key = "entry-$vehicleId-${editKind ?: "new"}-${editId ?: 0}") { c ->
        EntryEditViewModel(c.vehicleRepository, c.entryRepository, c.telemetryRepository, vehicleId, editKind, editId)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    val vehicle = state.vehicle
    val system = vehicle?.unitSystem
    val dUnit = system?.let { UnitConversions.distanceUnit(it) } ?: ""
    val vUnit = system?.let { UnitConversions.volumeUnit(it) } ?: ""
    val currency = vehicle?.currency ?: ""
    val lastOdometerHint = state.lastOdometerKm?.let { km ->
        system?.let { "Last: ${formatOdometer(UnitConversions.kmToDisplay(km, it))} $dUnit" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit entry" else "Add entry") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            // Pinned above the keyboard so saving never requires dismissing the
            // keyboard or scrolling to the bottom of the form.
            if (vehicle != null) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { vm.save() },
                        enabled = state.isValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(16.dp),
                    ) {
                        Text(if (state.isEditing) "Save changes" else "Add entry")
                    }
                }
            }
        },
    ) { padding ->
        if (vehicle == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text(if (state.loaded) "Vehicle not found." else "Loading…") }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Kind selector (only when creating and more than one kind is allowed)
            if (!state.isEditing && state.allowedKinds.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.allowedKinds.forEach { kind ->
                        FilterChip(
                            selected = state.kind == kind,
                            onClick = { vm.setKind(kind) },
                            label = { Text(kind.label()) },
                        )
                    }
                }
            }

            DatePickerField(
                dateMillis = state.dateMillis,
                onDateChange = { d -> vm.update { it.copy(dateMillis = d) } },
            )

            when (state.kind) {
                EntryKind.FUEL -> {
                    LabeledTextField(
                        value = state.odometer,
                        onValueChange = { v -> vm.update { it.copy(odometer = v) } },
                        label = "Odometer",
                        keyboardType = KeyboardType.Decimal,
                        suffix = dUnit,
                        isError = state.odometer.toDoubleOrNull() == null,
                        supportingText = lastOdometerHint,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = state.volume,
                            onValueChange = { v -> vm.editCostField(CostField.QUANTITY, v) },
                            label = "Volume",
                            keyboardType = KeyboardType.Decimal,
                            suffix = vUnit,
                            modifier = Modifier.weight(1f),
                        )
                        LabeledTextField(
                            value = state.totalCost,
                            onValueChange = { v -> vm.editCostField(CostField.TOTAL, v) },
                            label = "Total cost",
                            keyboardType = KeyboardType.Decimal,
                            suffix = currency,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LabeledTextField(
                        value = state.pricePerUnit,
                        onValueChange = { v -> vm.editCostField(CostField.PRICE, v) },
                        label = "Price per $vUnit",
                        keyboardType = KeyboardType.Decimal,
                        suffix = if (currency.isNotEmpty()) "$currency/$vUnit" else null,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Filled to full", modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.isFullTank,
                            onCheckedChange = { c -> vm.update { it.copy(isFullTank = c) } },
                        )
                    }
                    Text(
                        "Mark full fills to get accurate consumption.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    EnumDropdown(
                        label = "Fuel type",
                        options = FuelType.entries,
                        selected = state.fuelType,
                        onSelected = { f -> vm.update { it.copy(fuelType = f) } },
                        labelOf = { it.label() },
                    )
                    LabeledTextField(
                        value = state.station,
                        onValueChange = { v -> vm.update { it.copy(station = v) } },
                        label = "Station (optional)",
                    )
                }

                EntryKind.CHARGE -> {
                    LabeledTextField(
                        value = state.odometer,
                        onValueChange = { v -> vm.update { it.copy(odometer = v) } },
                        label = "Odometer",
                        keyboardType = KeyboardType.Decimal,
                        suffix = dUnit,
                        isError = state.odometer.toDoubleOrNull() == null,
                        supportingText = lastOdometerHint,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = state.energyKwh,
                            onValueChange = { v -> vm.editCostField(CostField.QUANTITY, v) },
                            label = "Energy",
                            keyboardType = KeyboardType.Decimal,
                            suffix = "kWh",
                            modifier = Modifier.weight(1f),
                        )
                        LabeledTextField(
                            value = state.totalCost,
                            onValueChange = { v -> vm.editCostField(CostField.TOTAL, v) },
                            label = "Total cost",
                            keyboardType = KeyboardType.Decimal,
                            suffix = currency,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LabeledTextField(
                        value = state.pricePerUnit,
                        onValueChange = { v -> vm.editCostField(CostField.PRICE, v) },
                        label = "Price per kWh",
                        keyboardType = KeyboardType.Decimal,
                        suffix = if (currency.isNotEmpty()) "$currency/kWh" else null,
                    )
                    EnumDropdown(
                        label = "Charge type",
                        options = ChargeType.entries,
                        selected = state.chargeType,
                        onSelected = { t -> vm.update { it.copy(chargeType = t) } },
                        labelOf = { it.label() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = state.startPct,
                            onValueChange = { v -> vm.update { it.copy(startPct = v.filter(Char::isDigit).take(3)) } },
                            label = "Start %",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        LabeledTextField(
                            value = state.endPct,
                            onValueChange = { v -> vm.update { it.copy(endPct = v.filter(Char::isDigit).take(3)) } },
                            label = "End %",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LabeledTextField(
                        value = state.location,
                        onValueChange = { v -> vm.update { it.copy(location = v) } },
                        label = "Location (optional)",
                    )
                }

                EntryKind.EXPENSE -> {
                    EnumDropdown(
                        label = "Category",
                        options = ExpenseCategory.entries,
                        selected = state.category,
                        onSelected = { cat ->
                            vm.update {
                                // Financing is billed the same amount every month by default.
                                val recurrence = if (cat == ExpenseCategory.FINANCING && it.recurrence == Recurrence.NONE) {
                                    Recurrence.MONTHLY
                                } else {
                                    it.recurrence
                                }
                                it.copy(category = cat, recurrence = recurrence)
                            }
                        },
                        labelOf = { it.label() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = state.totalCost,
                            onValueChange = { v -> vm.update { it.copy(totalCost = v) } },
                            label = "Total cost",
                            keyboardType = KeyboardType.Decimal,
                            suffix = currency,
                            modifier = Modifier.weight(1f),
                        )
                        LabeledTextField(
                            value = state.odometer,
                            onValueChange = { v -> vm.update { it.copy(odometer = v) } },
                            label = "Odometer (optional)",
                            keyboardType = KeyboardType.Decimal,
                            suffix = dUnit,
                            supportingText = lastOdometerHint,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LabeledTextField(
                        value = state.vendor,
                        onValueChange = { v -> vm.update { it.copy(vendor = v) } },
                        label = "Vendor (optional)",
                    )
                    LabeledTextField(
                        value = state.description,
                        onValueChange = { v -> vm.update { it.copy(description = v) } },
                        label = "Description (optional)",
                        singleLine = false,
                    )
                    EnumDropdown(
                        label = "Recurrence",
                        options = Recurrence.entries,
                        selected = state.recurrence,
                        onSelected = { r -> vm.update { it.copy(recurrence = r) } },
                        labelOf = { it.label() },
                    )
                    if (state.recurrence != Recurrence.NONE) {
                        OptionalDatePickerField(
                            dateMillis = state.recurrenceEndMillis,
                            onDateChange = { d -> vm.update { it.copy(recurrenceEndMillis = d) } },
                            label = "Ends",
                            emptyText = "ongoing",
                        )
                    }
                }
            }

            if (state.kind != EntryKind.EXPENSE) {
                LabeledTextField(
                    value = state.note,
                    onValueChange = { v -> vm.update { it.copy(note = v) } },
                    label = "Note (optional)",
                    singleLine = false,
                )
            }
        }
    }
}

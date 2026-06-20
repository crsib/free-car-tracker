package me.crsib.freecartracker.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.crsib.freecartracker.ui.util.formatDate

/** Like [DatePickerField] but the value is optional; the dialog offers a Clear action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionalDatePickerField(
    dateMillis: Long?,
    onDateChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Date",
    emptyText: String = "none",
) {
    var showDialog by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showDialog = true }, modifier = modifier.fillMaxWidth()) {
        Text("$label: ${dateMillis?.let { formatDate(it) } ?: emptyText}")
    }
    if (showDialog) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    onDateChange(pickerState.selectedDateMillis)
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDateChange(null)
                    showDialog = false
                }) { Text("Clear") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    dateMillis: Long,
    onDateChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Date",
) {
    var showDialog by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showDialog = true }, modifier = modifier.fillMaxWidth()) {
        Text("$label: ${formatDate(dateMillis)}")
    }
    if (showDialog) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(onDateChange)
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

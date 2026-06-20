package me.crsib.freecartracker.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.crsib.freecartracker.data.backup.RestoreMode
import me.crsib.freecartracker.data.prefs.ThemeMode
import me.crsib.freecartracker.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenWebDav: () -> Unit,
) {
    val vm = containerViewModel { c -> SettingsViewModel(c.preferences, c.backupRepository, c.vehicleSelection) }
    val windowDays by vm.windowDays.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Mode chosen before launching the import picker.
    var pendingMode by remember { mutableStateOf(RestoreMode.MERGE) }

    val exportJsonLauncher = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching {
                val json = vm.buildJson(System.currentTimeMillis())
                writeText(context, uri, json)
                "Backup exported."
            }.getOrElse { "Export failed: ${it.message}" }
            snackbar.showSnackbar(msg)
        }
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching {
                writeText(context, uri, vm.buildCsv())
                "CSV exported."
            }.getOrElse { "Export failed: ${it.message}" }
            snackbar.showSnackbar(msg)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching {
                val text = readText(context, uri)
                vm.restoreFromText(text, pendingMode)
            }.getOrElse { "Import failed: ${it.message}" }
            snackbar.showSnackbar(msg)
        }
    }
    val importDrivvoLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching {
                val text = readText(context, uri)
                vm.importDrivvo(text)
            }.getOrElse { "Import failed: ${it.message}" }
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    Text("Choose how the app looks.", style = MaterialTheme.typography.bodySmall)
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                label = { Text(themeModeLabel(mode)) },
                            )
                        }
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Dashboard window", style = MaterialTheme.typography.titleMedium)
                    Text("Period used for spend and consumption totals.", style = MaterialTheme.typography.bodySmall)
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(7, 14, 30, 60, 90).forEach { days ->
                            FilterChip(
                                selected = windowDays == days,
                                onClick = { vm.setWindowDays(days) },
                                label = { Text("${days}d") },
                            )
                        }
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
                    Text("Export a full backup or import one you saved earlier.", style = MaterialTheme.typography.bodySmall)

                    Button(
                        onClick = { exportJsonLauncher.launch("car-tracker-backup.json") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Export backup (JSON)") }

                    OutlinedButton(
                        onClick = { exportCsvLauncher.launch("car-tracker-entries.csv") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Export entries (CSV)") }

                    OutlinedButton(
                        onClick = {
                            pendingMode = RestoreMode.MERGE
                            importLauncher.launch(arrayOf("application/json"))
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Import & merge") }

                    OutlinedButton(
                        onClick = {
                            pendingMode = RestoreMode.REPLACE
                            importLauncher.launch(arrayOf("application/json"))
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Import & replace all") }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Import from Drivvo", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Import a Drivvo CSV export into the selected vehicle. Refuelling, charging, " +
                            "expense and service rows are added (merged) using the vehicle's currency.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            importDrivvoLauncher.launch(
                                arrayOf(
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "text/plain",
                                    "application/vnd.ms-excel",
                                    "*/*",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Import Drivvo CSV") }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("WebDAV sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Automatically back up to your own WebDAV server (Nextcloud, ownCloud, …).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onOpenWebDav,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Configure WebDAV") }
                }
            }
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private suspend fun writeText(context: android.content.Context, uri: Uri, text: String) =
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            ?: error("Could not open file for writing")
    }

private suspend fun readText(context: android.content.Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Could not open file for reading")
    }

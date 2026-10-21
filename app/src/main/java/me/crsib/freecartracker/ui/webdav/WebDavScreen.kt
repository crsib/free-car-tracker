package me.crsib.freecartracker.ui.webdav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.data.webdav.S3Config
import me.crsib.freecartracker.data.webdav.SyncBackend
import me.crsib.freecartracker.data.webdav.WebDavBackupWorker
import me.crsib.freecartracker.ui.components.LabeledTextField
import me.crsib.freecartracker.ui.containerViewModel
import me.crsib.freecartracker.ui.util.formatDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavScreen(onBack: () -> Unit) {
    val vm = containerViewModel { c -> WebDavViewModel(c.webDavSyncManager) }
    val state by vm.state.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackendSelector(
                selected = state.backend,
                onSelect = { b -> vm.update { it.copy(backend = b) } },
            )

            when (state.backend) {
                SyncBackend.WEBDAV -> {
                    Text(
                        "Enter the URL of a WebDAV folder (e.g. https://cloud.example.com/remote.php/dav/files/me/CarTracker/).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LabeledTextField(
                        value = state.url,
                        onValueChange = { v -> vm.update { it.copy(url = v) } },
                        label = "WebDAV folder URL",
                        keyboardType = KeyboardType.Uri,
                    )
                    LabeledTextField(
                        value = state.username,
                        onValueChange = { v -> vm.update { it.copy(username = v) } },
                        label = "Username",
                    )
                    PasswordField(
                        value = state.password,
                        onValueChange = { v -> vm.update { it.copy(password = v) } },
                        label = "Password / app token",
                    )
                }
                SyncBackend.S3 -> S3Fields(state.s3, onChange = vm::updateS3)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Daily automatic backup")
                    Text(
                        "Uploads a backup once a day when online.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.autoBackup,
                    onCheckedChange = { checked ->
                        vm.update { it.copy(autoBackup = checked) }
                        vm.save()
                        if (checked) WebDavBackupWorker.schedule(context) else WebDavBackupWorker.cancel(context)
                    },
                )
            }

            if (state.lastBackupMillis > 0) {
                Text(
                    "Last backup: ${formatDate(state.lastBackupMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SyncStatusLine(syncStatus)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { vm.testConnection() },
                    enabled = !state.busy && state.isConfigured,
                    modifier = Modifier.weight(1f),
                ) { Text("Test") }
                Button(
                    onClick = { vm.save() },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendSelector(selected: SyncBackend, onSelect: (SyncBackend) -> Unit) {
    val options = listOf(SyncBackend.WEBDAV to "WebDAV", SyncBackend.S3 to "S3")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (backend, label) ->
            SegmentedButton(
                selected = backend == selected,
                onClick = { onSelect(backend) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun S3Fields(s3: S3Config, onChange: ((S3Config) -> S3Config) -> Unit) {
    Text(
        "Any S3-compatible bucket: AWS, Cloudflare R2, Backblaze B2, MinIO, Yandex Object Storage, … " +
            "Use an access key limited to this bucket.",
        style = MaterialTheme.typography.bodySmall,
    )
    LabeledTextField(
        value = s3.endpoint,
        onValueChange = { v -> onChange { it.copy(endpoint = v) } },
        label = "Endpoint",
        keyboardType = KeyboardType.Uri,
        supportingText = "e.g. https://s3.eu-central-1.amazonaws.com — leave empty for AWS",
    )
    LabeledTextField(
        value = s3.region,
        onValueChange = { v -> onChange { it.copy(region = v) } },
        label = "Region",
        supportingText = "R2: auto · MinIO: us-east-1",
    )
    LabeledTextField(
        value = s3.bucket,
        onValueChange = { v -> onChange { it.copy(bucket = v) } },
        label = "Bucket",
    )
    LabeledTextField(
        value = s3.prefix,
        onValueChange = { v -> onChange { it.copy(prefix = v) } },
        label = "Folder in bucket (optional)",
        supportingText = "e.g. CarTracker",
    )
    LabeledTextField(
        value = s3.accessKeyId,
        onValueChange = { v -> onChange { it.copy(accessKeyId = v) } },
        label = "Access key ID",
    )
    PasswordField(
        value = s3.secretAccessKey,
        onValueChange = { v -> onChange { it.copy(secretAccessKey = v) } },
        label = "Secret access key",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Path-style addressing")
            Text(
                "Turn off only if your provider requires bucket.host URLs.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = s3.pathStyle,
            onCheckedChange = { checked -> onChange { it.copy(pathStyle = checked) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

package me.crsib.freecartracker.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.webdav.S3Config
import me.crsib.freecartracker.data.webdav.SyncBackend
import me.crsib.freecartracker.data.webdav.SyncStatus
import me.crsib.freecartracker.data.webdav.WebDavConfig
import me.crsib.freecartracker.data.webdav.WebDavSyncManager

data class WebDavFormState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val autoBackup: Boolean = false,
    val lastBackupMillis: Long = 0,
    val backend: SyncBackend = SyncBackend.WEBDAV,
    val s3: S3Config = S3Config(),
    val busy: Boolean = false,
    val message: String? = null,
) {
    fun toConfig(lastBackup: Long = lastBackupMillis) =
        WebDavConfig(url, username, password, autoBackup, lastBackup, backend, s3)

    val isConfigured: Boolean get() = toConfig().isConfigured
}

class WebDavViewModel(
    private val manager: WebDavSyncManager,
) : ViewModel() {

    private val _state = MutableStateFlow(WebDavFormState())
    val state: StateFlow<WebDavFormState> = _state.asStateFlow()

    /** Live sync step, so this screen shows background worker progress while it is open. */
    val syncStatus: StateFlow<SyncStatus> = manager.status

    init {
        val cfg = manager.config()
        _state.value = WebDavFormState(
            url = cfg.url,
            username = cfg.username,
            password = cfg.password,
            autoBackup = cfg.autoBackup,
            lastBackupMillis = cfg.lastBackupMillis,
            backend = cfg.backend,
            s3 = cfg.s3,
        )
    }

    fun update(transform: (WebDavFormState) -> WebDavFormState) = _state.update(transform)

    fun updateS3(transform: (S3Config) -> S3Config) = _state.update { it.copy(s3 = transform(it.s3)) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun save() {
        manager.saveConfig(_state.value.toConfig())
        _state.update { it.copy(message = "Settings saved.") }
    }

    fun testConnection() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val result = manager.testConnection(_state.value.toConfig())
        _state.update {
            it.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { "Connection OK." },
                    onFailure = { e -> "Connection failed: ${e.message}" },
                ),
            )
        }
    }
}

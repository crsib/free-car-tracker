package me.crsib.freecartracker.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.webdav.WebDavConfig
import me.crsib.freecartracker.data.webdav.WebDavSyncManager

data class WebDavFormState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val autoBackup: Boolean = false,
    val lastBackupMillis: Long = 0,
    val busy: Boolean = false,
    val message: String? = null,
) {
    fun toConfig(lastBackup: Long = lastBackupMillis) =
        WebDavConfig(url, username, password, autoBackup, lastBackup)

    val isConfigured: Boolean get() = url.isNotBlank() && username.isNotBlank()
}

class WebDavViewModel(
    private val manager: WebDavSyncManager,
) : ViewModel() {

    private val _state = MutableStateFlow(WebDavFormState())
    val state: StateFlow<WebDavFormState> = _state.asStateFlow()

    init {
        val cfg = manager.config()
        _state.value = WebDavFormState(
            url = cfg.url,
            username = cfg.username,
            password = cfg.password,
            autoBackup = cfg.autoBackup,
            lastBackupMillis = cfg.lastBackupMillis,
        )
    }

    fun update(transform: (WebDavFormState) -> WebDavFormState) = _state.update(transform)

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

    /**
     * Manual bidirectional sync: push local cars/ledgers/trips, then pull whatever this device is
     * missing, and report the counts that moved. Same path the worker runs on each trigger.
     */
    fun syncNow(now: Long) = viewModelScope.launch {
        manager.saveConfig(_state.value.toConfig())
        _state.update { it.copy(busy = true) }
        val result = manager.syncNow(now)
        _state.update {
            it.copy(
                busy = false,
                lastBackupMillis = if (result.isSuccess) now else it.lastBackupMillis,
                message = result.fold(
                    onSuccess = { report -> "Synced. ${report.summary()}" },
                    onFailure = { e -> "Sync failed: ${e.message}" },
                ),
            )
        }
    }
}

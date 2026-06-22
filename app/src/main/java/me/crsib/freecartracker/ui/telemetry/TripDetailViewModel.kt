package me.crsib.freecartracker.ui.telemetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.crsib.freecartracker.telemetry.data.TelemetryRepository
import me.crsib.freecartracker.telemetry.data.TripData

data class TripDetailUiState(
    val loading: Boolean = true,
    val data: TripData? = null,
)

class TripDetailViewModel(
    private val repo: TelemetryRepository,
    private val tripId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(TripDetailUiState())
    val state: StateFlow<TripDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = TripDetailUiState(loading = false, data = repo.getTripData(tripId))
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteTrip(tripId)
            onDone()
        }
    }
}

package me.crsib.freecartracker.ui.telemetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.crsib.freecartracker.telemetry.data.TelemetryRepository
import me.crsib.freecartracker.telemetry.data.TripData
import me.crsib.freecartracker.ui.util.formatNumber

data class TripDetailUiState(
    val loading: Boolean = true,
    val data: TripData? = null,
    val recomputing: Boolean = false,
    /** One-shot user message (e.g. recompute result); cleared via [messageShown]. */
    val message: String? = null,
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

    /**
     * Rebuild this trip's summary metrics from its stored samples (e.g. to apply odometer-based
     * distance to a trip recorded before that fix), then reload the detail so the new numbers show.
     */
    fun recompute() {
        if (_state.value.recomputing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(recomputing = true)
            val agg = repo.recomputeAggregates(tripId)
            val data = repo.getTripData(tripId)
            val message = when {
                agg == null -> "Nothing to recalculate"
                agg.distanceKm != null -> "Metrics recalculated · ${formatNumber(agg.distanceKm)} km"
                else -> "Metrics recalculated"
            }
            _state.value = _state.value.copy(data = data, recomputing = false, message = message)
        }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteTrip(tripId)
            onDone()
        }
    }
}

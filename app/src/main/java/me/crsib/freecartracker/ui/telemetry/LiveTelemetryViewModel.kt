package me.crsib.freecartracker.ui.telemetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import me.crsib.freecartracker.data.VehicleSelection
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.telemetry.service.LiveTelemetry
import me.crsib.freecartracker.telemetry.service.RecordingState
import me.crsib.freecartracker.telemetry.service.TripRecorder

data class LiveUiState(
    val recordingState: RecordingState = RecordingState.IDLE,
    val live: LiveTelemetry = LiveTelemetry(),
    val selectedVehicle: Vehicle? = null,
)

/** Surfaces the [TripRecorder]'s live state and the selected vehicle to the dashboard. */
class LiveTelemetryViewModel(
    recorder: TripRecorder,
    selection: VehicleSelection,
) : ViewModel() {

    val uiState: StateFlow<LiveUiState> =
        combine(
            recorder.recordingState,
            recorder.liveState,
            selection.observeSelected(),
        ) { state, live, vehicle ->
            LiveUiState(recordingState = state, live = live, selectedVehicle = vehicle)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())
}

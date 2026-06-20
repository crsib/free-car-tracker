package me.crsib.freecartracker.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.data.repository.VehicleRepository

data class VehicleListUiState(
    val active: List<Vehicle> = emptyList(),
    val archived: List<Vehicle> = emptyList(),
    val selectedId: Long? = null,
)

class VehicleListViewModel(
    private val repo: VehicleRepository,
    private val prefs: AppPreferences,
) : ViewModel() {

    val uiState: StateFlow<VehicleListUiState> =
        combine(repo.observeAll(), prefs.selectedVehicleId) { vehicles, selectedId ->
            VehicleListUiState(
                active = vehicles.filter { !it.isArchived },
                archived = vehicles.filter { it.isArchived },
                selectedId = selectedId,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleListUiState())

    fun setArchived(vehicle: Vehicle, archived: Boolean) = viewModelScope.launch {
        repo.setArchived(vehicle, archived)
    }

    fun setSelected(vehicle: Vehicle) = viewModelScope.launch {
        prefs.setSelectedVehicleId(vehicle.id)
    }

    fun delete(vehicle: Vehicle) = viewModelScope.launch {
        repo.delete(vehicle)
    }
}

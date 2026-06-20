package me.crsib.freecartracker.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.data.repository.VehicleRepository
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.FuelType
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.UnitSystem

data class VehicleFormState(
    val id: Long = 0,
    val name: String = "",
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val plate: String = "",
    val powertrain: Powertrain = Powertrain.ICE,
    val fuelType: FuelType = FuelType.PETROL,
    val tankCapacity: String = "",
    val batteryCapacity: String = "",
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val currency: String = "USD",
    val initialOdometer: String = "",
    val createdAt: Long = 0,
    val isArchived: Boolean = false,
    val loaded: Boolean = false,
    val saved: Boolean = false,
) {
    val isValid: Boolean get() = name.isNotBlank() && currency.length == 3
}

class VehicleEditViewModel(
    private val repo: VehicleRepository,
    private val prefs: AppPreferences,
    private val vehicleId: Long?,
) : ViewModel() {

    private val _state = MutableStateFlow(VehicleFormState())
    val state: StateFlow<VehicleFormState> = _state.asStateFlow()

    val isNew: Boolean get() = vehicleId == null

    init {
        if (vehicleId == null) {
            _state.update { it.copy(loaded = true) }
        } else {
            viewModelScope.launch { load(vehicleId) }
        }
    }

    private suspend fun load(id: Long) {
        val v = repo.getById(id) ?: run {
            _state.update { it.copy(loaded = true) }
            return
        }
        _state.value = VehicleFormState(
            id = v.id,
            name = v.name,
            make = v.make.orEmpty(),
            model = v.model.orEmpty(),
            year = v.year?.toString().orEmpty(),
            plate = v.plate.orEmpty(),
            powertrain = v.powertrain,
            fuelType = v.fuelType ?: FuelType.PETROL,
            tankCapacity = v.tankCapacityL
                ?.let { UnitConversions.litresToDisplay(it, v.unitSystem) }
                ?.let { trim(it) } ?: "",
            batteryCapacity = v.batteryCapacityKwh?.let { trim(it) } ?: "",
            unitSystem = v.unitSystem,
            currency = v.currency,
            initialOdometer = trim(UnitConversions.kmToDisplay(v.initialOdometerKm, v.unitSystem)),
            createdAt = v.createdAt,
            isArchived = v.isArchived,
            loaded = true,
        )
    }

    fun update(transform: (VehicleFormState) -> VehicleFormState) = _state.update(transform)

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (!s.isValid) return@launch
        val system = s.unitSystem
        val tankL = s.tankCapacity.toDoubleOrNull()
            ?.let { UnitConversions.displayToLitres(it, system) }
        val vehicle = Vehicle(
            id = s.id,
            name = s.name.trim(),
            make = s.make.trim().ifBlank { null },
            model = s.model.trim().ifBlank { null },
            year = s.year.toIntOrNull(),
            plate = s.plate.trim().ifBlank { null },
            powertrain = s.powertrain,
            fuelType = if (s.powertrain.usesFuel) s.fuelType else null,
            tankCapacityL = if (s.powertrain.usesFuel) tankL else null,
            batteryCapacityKwh = if (s.powertrain.usesCharging) s.batteryCapacity.toDoubleOrNull() else null,
            unitSystem = system,
            currency = s.currency.uppercase().trim(),
            initialOdometerKm = s.initialOdometer.toDoubleOrNull()
                ?.let { UnitConversions.displayToKm(it, system) } ?: 0.0,
            isArchived = s.isArchived,
            createdAt = if (isNew) System.currentTimeMillis() else s.createdAt,
        )
        if (isNew) {
            val newId = repo.add(vehicle)
            prefs.setSelectedVehicleId(newId)
        } else {
            repo.update(vehicle)
        }
        _state.update { it.copy(saved = true) }
    }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

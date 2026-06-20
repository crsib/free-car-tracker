package me.crsib.freecartracker.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.data.repository.VehicleRepository

/**
 * Resolves the currently-selected vehicle: the one persisted in preferences if it is still
 * active, otherwise the first active vehicle (or null when none exist).
 */
class VehicleSelection(
    private val vehicleRepo: VehicleRepository,
    private val prefs: AppPreferences,
) {
    fun observeSelected(): Flow<Vehicle?> =
        combine(vehicleRepo.observeActive(), prefs.selectedVehicleId) { active, selectedId ->
            active.firstOrNull { it.id == selectedId } ?: active.firstOrNull()
        }

    fun observeActive(): Flow<List<Vehicle>> = vehicleRepo.observeActive()

    suspend fun select(id: Long) = prefs.setSelectedVehicleId(id)
}

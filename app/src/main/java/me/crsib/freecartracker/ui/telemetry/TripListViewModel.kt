package me.crsib.freecartracker.ui.telemetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.VehicleSelection
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.telemetry.data.TelemetryRepository
import me.crsib.freecartracker.telemetry.data.entity.Trip

data class TripListUiState(
    val loading: Boolean = true,
    val vehicle: Vehicle? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val trips: List<Trip> = emptyList(),
    /** Final odometer (km) per trip id, for trips whose telemetry reported the odometer. */
    val odometerByTrip: Map<Long, Double> = emptyMap(),
    /** Average fuel usage (L/100km) per trip id: the aggregate, or a tank-drop estimate. */
    val avgFuelUsageByTrip: Map<Long, Double> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TripListViewModel(
    private val selection: VehicleSelection,
    private val telemetryRepo: TelemetryRepository,
) : ViewModel() {

    val uiState: StateFlow<TripListUiState> =
        selection.observeSelected()
            .flatMapLatest { vehicle ->
                if (vehicle == null) {
                    flowOf(TripListUiState(loading = false))
                } else {
                    telemetryRepo.observeTrips(vehicle.id).map { trips ->
                        val spans = telemetryRepo.fuelSpansByTrip(vehicle.id)
                        val usage = trips.associate { trip ->
                            val span = spans[trip.id]
                            trip.id to (
                                trip.avgConsumptionL100
                                    ?: tankAvgUsageL100(span?.startPct, span?.endPct, vehicle.tankCapacityL, trip.distanceKm)
                                )
                        }.filterValues { it != null }.mapValues { it.value!! }
                        TripListUiState(
                            loading = false,
                            vehicle = vehicle,
                            trips = trips,
                            odometerByTrip = telemetryRepo.finalOdometerByTrip(vehicle.id),
                            avgFuelUsageByTrip = usage,
                        )
                    }
                }
            }
            .combine(selection.observeActive()) { state, vehicles ->
                state.copy(vehicles = vehicles)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripListUiState())

    fun selectVehicle(id: Long) = viewModelScope.launch { selection.select(id) }

    fun deleteTrip(id: Long) = viewModelScope.launch { telemetryRepo.deleteTrip(id) }
}

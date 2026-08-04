package me.crsib.freecartracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.VehicleSelection
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.repository.EntryRepository
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.ui.entries.EntryCardItem
import me.crsib.freecartracker.ui.entries.EntryCards

data class HistoryUiState(
    val loading: Boolean = true,
    val vehicle: Vehicle? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val items: List<EntryCardItem> = emptyList(),
    val filter: Set<EntryKind> = EntryKind.entries.toSet(),
    val query: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val selection: VehicleSelection,
    private val entryRepo: EntryRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(EntryKind.entries.toSet())
    private val query = MutableStateFlow("")

    val uiState: StateFlow<HistoryUiState> =
        selection.observeSelected().flatMapLatest { vehicle ->
            if (vehicle == null) {
                combine(filter, query) { f, q -> HistoryUiState(loading = false, filter = f, query = q) }
            } else {
                combine(
                    entryRepo.observeFuel(vehicle.id),
                    entryRepo.observeCharge(vehicle.id),
                    entryRepo.observeExpenses(vehicle.id),
                    filter,
                    query,
                ) { fuel, charge, expenses, f, q ->
                    val filtered = EntryCards.build(vehicle, fuel, charge, expenses)
                        .filter { it.kind in f }
                        .filter { q.isBlank() || it.search.contains(q, ignoreCase = true) }
                    HistoryUiState(
                        loading = false,
                        vehicle = vehicle,
                        items = filtered,
                        filter = f,
                        query = q,
                    )
                }
            }
        }.combine(selection.observeActive()) { state, vehicles ->
            state.copy(vehicles = vehicles)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun selectVehicle(id: Long) = viewModelScope.launch { selection.select(id) }

    fun toggleFilter(kind: EntryKind) {
        filter.value = filter.value.toMutableSet().apply {
            if (contains(kind)) remove(kind) else add(kind)
            if (isEmpty()) addAll(EntryKind.entries) // never allow an empty filter
        }
    }

    fun setQuery(q: String) { query.value = q }

    fun delete(item: EntryCardItem) = viewModelScope.launch {
        when (item.kind) {
            EntryKind.FUEL -> entryRepo.deleteFuel(item.id)
            EntryKind.CHARGE -> entryRepo.deleteCharge(item.id)
            EntryKind.EXPENSE -> entryRepo.deleteExpense(item.id)
        }
    }
}

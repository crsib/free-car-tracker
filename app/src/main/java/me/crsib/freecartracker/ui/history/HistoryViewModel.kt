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
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.domain.model.Recurrence
import me.crsib.freecartracker.ui.util.formatDate
import me.crsib.freecartracker.ui.util.formatNumber
import me.crsib.freecartracker.ui.util.label

data class HistoryItem(
    val kind: EntryKind,
    val id: Long,
    val dateMillis: Long,
    val title: String,
    val subtitle: String,
    val amountMinor: Long,
    val currency: String,
    val search: String,
)

data class HistoryUiState(
    val loading: Boolean = true,
    val vehicle: Vehicle? = null,
    val items: List<HistoryItem> = emptyList(),
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
                    val system = vehicle.unitSystem
                    val dUnit = UnitConversions.distanceUnit(system)
                    val vUnit = UnitConversions.volumeUnit(system)
                    val all = buildList {
                        fuel.forEach { e ->
                            val odo = formatNumber(UnitConversions.kmToDisplay(e.odometerKm, system), 0)
                            val vol = formatNumber(UnitConversions.litresToDisplay(e.volumeL, system), 1)
                            add(
                                HistoryItem(
                                    kind = EntryKind.FUEL,
                                    id = e.id,
                                    dateMillis = e.dateMillis,
                                    title = "Fuel",
                                    subtitle = "$vol $vUnit · $odo $dUnit" + if (e.isFullTank) " · full" else "",
                                    amountMinor = e.totalCostMinor,
                                    currency = e.currency,
                                    search = "fuel ${e.station.orEmpty()} ${e.note.orEmpty()}",
                                ),
                            )
                        }
                        charge.forEach { e ->
                            add(
                                HistoryItem(
                                    kind = EntryKind.CHARGE,
                                    id = e.id,
                                    dateMillis = e.dateMillis,
                                    title = "Charging",
                                    subtitle = "${formatNumber(e.energyKwh, 1)} kWh" +
                                        (e.location?.let { " · $it" } ?: ""),
                                    amountMinor = e.totalCostMinor,
                                    currency = e.currency,
                                    search = "charge ${e.location.orEmpty()} ${e.note.orEmpty()}",
                                ),
                            )
                        }
                        expenses.forEach { e ->
                            add(
                                HistoryItem(
                                    kind = EntryKind.EXPENSE,
                                    id = e.id,
                                    dateMillis = e.dateMillis,
                                    title = e.category.label(),
                                    subtitle = listOfNotNull(
                                        e.recurrence.takeIf { it != Recurrence.NONE }?.let { rec ->
                                            rec.label() + (e.recurrenceEndMillis?.let { " until ${formatDate(it)}" } ?: "")
                                        },
                                        e.vendor,
                                        e.description,
                                    ).joinToString(" · "),
                                    amountMinor = e.totalCostMinor,
                                    currency = e.currency,
                                    search = "${e.category.label()} ${e.vendor.orEmpty()} ${e.description.orEmpty()}",
                                ),
                            )
                        }
                    }
                    val filtered = all
                        .filter { it.kind in f }
                        .filter { q.isBlank() || it.search.contains(q, ignoreCase = true) }
                        .sortedByDescending { it.dateMillis }
                    HistoryUiState(
                        loading = false,
                        vehicle = vehicle,
                        items = filtered,
                        filter = f,
                        query = q,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun toggleFilter(kind: EntryKind) {
        filter.value = filter.value.toMutableSet().apply {
            if (contains(kind)) remove(kind) else add(kind)
            if (isEmpty()) addAll(EntryKind.entries) // never allow an empty filter
        }
    }

    fun setQuery(q: String) { query.value = q }

    fun delete(item: HistoryItem) = viewModelScope.launch {
        when (item.kind) {
            EntryKind.FUEL -> entryRepo.deleteFuel(item.id)
            EntryKind.CHARGE -> entryRepo.deleteCharge(item.id)
            EntryKind.EXPENSE -> entryRepo.deleteExpense(item.id)
        }
    }
}

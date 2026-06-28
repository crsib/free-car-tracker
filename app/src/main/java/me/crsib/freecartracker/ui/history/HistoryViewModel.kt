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
import me.crsib.freecartracker.domain.ChargePoint
import me.crsib.freecartracker.domain.EconomyCalculator
import me.crsib.freecartracker.domain.FuelPoint
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.domain.model.Recurrence
import me.crsib.freecartracker.ui.util.formatDate
import me.crsib.freecartracker.ui.util.formatNumber
import me.crsib.freecartracker.ui.util.label

/** Which icon a [HistoryStat] shows; mapped to an actual vector in the UI layer. */
enum class StatIcon { VOLUME, ENERGY, ODOMETER, FULL, CONSUMPTION, LOCATION }

/** One labelled stat on a history card: an icon and a short value kept on a single line. */
data class HistoryStat(val icon: StatIcon, val text: String)

data class HistoryItem(
    val kind: EntryKind,
    val id: Long,
    val dateMillis: Long,
    val title: String,
    /** Free-text secondary line (used for expenses). */
    val subtitle: String,
    /** Icon+value chips (used for fuel/charge so values never wrap mid-token). */
    val stats: List<HistoryStat>,
    val amountMinor: Long,
    val currency: String,
    val search: String,
)

data class HistoryUiState(
    val loading: Boolean = true,
    val vehicle: Vehicle? = null,
    val vehicles: List<Vehicle> = emptyList(),
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
                    val ecoUnit = UnitConversions.fuelEconomyUnit(system)
                    val elUnit = UnitConversions.electricEconomyUnit(system)

                    // Per-entry average consumption: each economy segment closes at a fill/charge,
                    // identified by its odometer, so key by the closing odometer to attach it.
                    val fuelEcoByOdo = EconomyCalculator
                        .fuelSegments(fuel.map { FuelPoint(it.odometerKm, it.volumeL, it.isFullTank, it.dateMillis) })
                        .associateBy({ it.endOdometerKm }, { it.litresPer100Km })
                    val chargeEcoByOdo = EconomyCalculator
                        .chargeSegments(charge.map { ChargePoint(it.odometerKm, it.energyKwh, it.dateMillis) })
                        .associateBy({ it.endOdometerKm }, { it.kwhPer100Km })

                    val all = buildList {
                        fuel.forEach { e ->
                            val odo = formatNumber(UnitConversions.kmToDisplay(e.odometerKm, system), 0)
                            val vol = formatNumber(UnitConversions.litresToDisplay(e.volumeL, system), 1)
                            val stats = buildList {
                                add(HistoryStat(StatIcon.VOLUME, "$vol $vUnit"))
                                add(HistoryStat(StatIcon.ODOMETER, "$odo $dUnit"))
                                if (e.isFullTank) add(HistoryStat(StatIcon.FULL, "full"))
                                fuelEcoByOdo[e.odometerKm]
                                    ?.let { UnitConversions.fuelEconomyToDisplay(it, system) }
                                    ?.let { add(HistoryStat(StatIcon.CONSUMPTION, "${formatNumber(it, 1)} $ecoUnit")) }
                            }
                            add(
                                HistoryItem(
                                    kind = EntryKind.FUEL,
                                    id = e.id,
                                    dateMillis = e.dateMillis,
                                    title = "Fuel",
                                    subtitle = "",
                                    stats = stats,
                                    amountMinor = e.totalCostMinor,
                                    currency = e.currency,
                                    search = "fuel ${e.station.orEmpty()} ${e.note.orEmpty()}",
                                ),
                            )
                        }
                        charge.forEach { e ->
                            val stats = buildList {
                                add(HistoryStat(StatIcon.ENERGY, "${formatNumber(e.energyKwh, 1)} kWh"))
                                e.location?.takeIf { it.isNotBlank() }?.let { add(HistoryStat(StatIcon.LOCATION, it)) }
                                e.odometerKm?.let { chargeEcoByOdo[it] }
                                    ?.let { UnitConversions.electricEconomyToDisplay(it, system) }
                                    ?.let { add(HistoryStat(StatIcon.CONSUMPTION, "${formatNumber(it, 1)} $elUnit")) }
                            }
                            add(
                                HistoryItem(
                                    kind = EntryKind.CHARGE,
                                    id = e.id,
                                    dateMillis = e.dateMillis,
                                    title = "Charging",
                                    subtitle = "",
                                    stats = stats,
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
                                    stats = emptyList(),
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

    fun delete(item: HistoryItem) = viewModelScope.launch {
        when (item.kind) {
            EntryKind.FUEL -> entryRepo.deleteFuel(item.id)
            EntryKind.CHARGE -> entryRepo.deleteCharge(item.id)
            EntryKind.EXPENSE -> entryRepo.deleteExpense(item.id)
        }
    }
}

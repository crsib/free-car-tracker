package me.crsib.freecartracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.VehicleSelection
import me.crsib.freecartracker.data.dao.CategoryTotal
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.prefs.AppPreferences
import me.crsib.freecartracker.data.repository.EntryRepository
import me.crsib.freecartracker.domain.EconomyCalculator
import me.crsib.freecartracker.domain.FuelPoint
import me.crsib.freecartracker.domain.RecurrenceProjector
import me.crsib.freecartracker.domain.model.EntryKind

data class TrendPoint(val dateMillis: Long, val litresPer100Km: Double)

data class RecentItem(
    val kind: EntryKind,
    val id: Long,
    val dateMillis: Long,
    val title: String,
    val amountMinor: Long,
    val currency: String,
)

data class DashboardUiState(
    val loading: Boolean = true,
    val vehicles: List<Vehicle> = emptyList(),
    val selected: Vehicle? = null,
    val windowDays: Int = AppPreferences.DEFAULT_WINDOW_DAYS,
    val latestFuelEconomyL100: Double? = null,
    val windowFuelEconomyL100: Double? = null,
    val electricEconomyKwh100: Double? = null,
    val windowDistanceKm: Double? = null,
    val totalSpendMinor: Long = 0,
    val costPerKmMinor: Double? = null,
    val buckets: List<CategoryTotal> = emptyList(),
    val trend: List<TrendPoint> = emptyList(),
    val recent: List<RecentItem> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val selection: VehicleSelection,
    private val entryRepo: EntryRepository,
    private val prefs: AppPreferences,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> =
        combine(
            selection.observeActive(),
            selection.observeSelected(),
            prefs.dashboardWindowDays,
        ) { vehicles, selected, days -> Triple(vehicles, selected, days) }
            .flatMapLatest { (vehicles, selected, days) ->
                if (selected == null) {
                    kotlinx.coroutines.flow.flowOf(
                        DashboardUiState(loading = false, vehicles = vehicles, windowDays = days),
                    )
                } else {
                    perVehicleState(vehicles, selected, days)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private fun perVehicleState(vehicles: List<Vehicle>, vehicle: Vehicle, days: Int) =
        combine(
            entryRepo.observeFuelByOdometer(vehicle.id),
            entryRepo.observeCharge(vehicle.id),
            entryRepo.observeExpenses(vehicle.id),
        ) { fuel, charge, expenses ->
            val now = System.currentTimeMillis()
            val since = now - days.toLong() * 24L * 60L * 60L * 1000L

            val fuelPoints = fuel.map {
                FuelPoint(it.odometerKm, it.volumeL, it.isFullTank, it.dateMillis)
            }
            val latestEconomy = EconomyCalculator.latestFuelEconomyL100(fuelPoints)

            // Distance covered within the window, from any odometer-bearing entry.
            val windowOdos = buildList {
                fuel.filter { it.dateMillis >= since }.forEach { add(it.odometerKm) }
                charge.filter { it.dateMillis >= since }.mapNotNull { it.odometerKm }.forEach { add(it) }
            }
            val windowDistance = EconomyCalculator.distanceSpanKm(windowOdos)

            val windowFuelL = fuel.filter { it.dateMillis >= since }.sumOf { it.volumeL }
            val windowKwh = charge.filter { it.dateMillis >= since }.sumOf { it.energyKwh }

            // Windowed spend. Recurring expenses (e.g. monthly financing) are expanded so each
            // billing date in the window counts, even though the entry was logged only once.
            val fuelCost = fuel.filter { it.dateMillis in since..now }.sumOf { it.totalCostMinor }
            val chargeCost = charge.filter { it.dateMillis in since..now }.sumOf { it.totalCostMinor }
            val expenseBuckets = LinkedHashMap<String, Long>()
            expenses.forEach { e ->
                val bills = RecurrenceProjector.occurrencesInWindow(
                    e.dateMillis, e.recurrence, since, now, e.recurrenceEndMillis,
                )
                if (bills > 0) {
                    expenseBuckets.merge(e.category.name, e.totalCostMinor * bills, Long::plus)
                }
            }
            val total = fuelCost + chargeCost + expenseBuckets.values.sum()
            val buckets = buildList {
                if (fuelCost > 0) add(CategoryTotal("FUEL", fuelCost))
                if (chargeCost > 0) add(CategoryTotal("CHARGE", chargeCost))
                expenseBuckets.forEach { (key, value) -> add(CategoryTotal(key, value)) }
            }.sortedByDescending { it.totalMinor }

            val trend = EconomyCalculator.fuelSegments(fuelPoints)
                .map { TrendPoint(it.endDateMillis, it.litresPer100Km) }
                .takeLast(12)

            DashboardUiState(
                loading = false,
                vehicles = vehicles,
                selected = vehicle,
                windowDays = days,
                latestFuelEconomyL100 = latestEconomy,
                windowFuelEconomyL100 = EconomyCalculator.per100Km(windowFuelL, windowDistance),
                electricEconomyKwh100 = EconomyCalculator.per100Km(windowKwh, windowDistance),
                windowDistanceKm = windowDistance,
                totalSpendMinor = total,
                costPerKmMinor = EconomyCalculator.costPerKmMinor(total, windowDistance),
                buckets = buckets,
                trend = trend,
                recent = recentItems(fuel, charge, expenses, vehicle),
            )
        }

    fun selectVehicle(id: Long) = viewModelScope.launch { selection.select(id) }

    private fun recentItems(
        fuel: List<me.crsib.freecartracker.data.entity.FuelEntry>,
        charge: List<me.crsib.freecartracker.data.entity.ChargeEntry>,
        expenses: List<me.crsib.freecartracker.data.entity.Expense>,
        vehicle: Vehicle,
    ): List<RecentItem> {
        val items = buildList {
            fuel.forEach {
                add(RecentItem(EntryKind.FUEL, it.id, it.dateMillis, "Fuel", it.totalCostMinor, it.currency))
            }
            charge.forEach {
                add(RecentItem(EntryKind.CHARGE, it.id, it.dateMillis, "Charge", it.totalCostMinor, it.currency))
            }
            expenses.forEach {
                add(RecentItem(EntryKind.EXPENSE, it.id, it.dateMillis, it.category.name, it.totalCostMinor, it.currency))
            }
        }
        return items.sortedByDescending { it.dateMillis }.take(8)
    }
}

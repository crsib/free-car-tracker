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
import me.crsib.freecartracker.domain.ChargePoint
import me.crsib.freecartracker.domain.EconomyCalculator
import me.crsib.freecartracker.domain.EnergyCostCalculator
import me.crsib.freecartracker.domain.EnergyCostShare
import me.crsib.freecartracker.domain.EnergyPurchase
import me.crsib.freecartracker.domain.EnergySource
import me.crsib.freecartracker.domain.FuelPoint
import me.crsib.freecartracker.domain.RecurrenceProjector
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.ui.entries.EntryCardItem
import me.crsib.freecartracker.ui.entries.EntryCards

data class TrendPoint(val dateMillis: Long, val litresPer100Km: Double)

data class DashboardUiState(
    val loading: Boolean = true,
    val vehicles: List<Vehicle> = emptyList(),
    val selected: Vehicle? = null,
    val windowDays: Int = AppPreferences.DEFAULT_WINDOW_DAYS,
    val latestFuelEconomyL100: Double? = null,
    val windowFuelEconomyL100: Double? = null,
    val electricEconomyKwh100: Double? = null,
    val windowDistanceKm: Double? = null,
    val windowFuelVolumeL: Double = 0.0,
    /** Per-segment fuel economy (L/100km) for completed intervals within the window. */
    val windowSegmentEconomiesL100: List<Double> = emptyList(),
    val windowEnergyKwh: Double = 0.0,
    /** Per-segment energy economy (kWh/100km) for completed charge intervals within the window. */
    val windowSegmentElectricKwh100: List<Double> = emptyList(),
    val totalSpendMinor: Long = 0,
    val costPerKmMinor: Double? = null,
    /** Cost per km of fuel + electricity only (no other expenses). */
    val energyCostPerKmMinor: Double? = null,
    /** Cost per km contributed by each fuel type and by electricity, biggest spender first. */
    val energyCostShares: List<EnergyCostShare> = emptyList(),
    val buckets: List<CategoryTotal> = emptyList(),
    val trend: List<TrendPoint> = emptyList(),
    val recent: List<EntryCardItem> = emptyList(),
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
            // ALL_TIME_DAYS (0) means no lower bound, so every entry counts.
            val since = if (days <= 0) Long.MIN_VALUE else now - days.toLong() * 24L * 60L * 60L * 1000L

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

            val windowFuel = fuel.filter { it.dateMillis in since..now }
            val windowCharge = charge.filter { it.dateMillis in since..now }
            val windowFuelL = fuel.filter { it.dateMillis >= since }.sumOf { it.volumeL }
            val windowKwh = charge.filter { it.dateMillis >= since }.sumOf { it.energyKwh }

            // Running cost split by energy source: one entry per fuel type plus electricity.
            val purchases = buildList {
                windowFuel.forEach {
                    add(EnergyPurchase(EnergySource.fuel(it.fuelType), it.totalCostMinor, it.volumeL))
                }
                windowCharge.forEach {
                    add(EnergyPurchase(EnergySource.ELECTRICITY, it.totalCostMinor, it.energyKwh))
                }
            }

            // Windowed spend. Recurring expenses (e.g. monthly financing) are expanded so each
            // billing date in the window counts, even though the entry was logged only once.
            val fuelCost = windowFuel.sumOf { it.totalCostMinor }
            val chargeCost = windowCharge.sumOf { it.totalCostMinor }
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

            val segments = EconomyCalculator.fuelSegments(fuelPoints)
            val trend = segments
                .filter { it.endDateMillis >= since }
                .map { TrendPoint(it.endDateMillis, it.litresPer100Km) }
                .takeLast(12)
            val windowSegmentEconomies = segments
                .filter { it.endDateMillis >= since }
                .map { it.litresPer100Km }

            val chargePoints = charge.map { ChargePoint(it.odometerKm, it.energyKwh, it.dateMillis) }
            val windowElectricEconomies = EconomyCalculator.chargeSegments(chargePoints)
                .filter { it.endDateMillis >= since }
                .map { it.kwhPer100Km }

            DashboardUiState(
                loading = false,
                vehicles = vehicles,
                selected = vehicle,
                windowDays = days,
                latestFuelEconomyL100 = latestEconomy,
                windowFuelEconomyL100 = EconomyCalculator.per100Km(windowFuelL, windowDistance),
                electricEconomyKwh100 = EconomyCalculator.per100Km(windowKwh, windowDistance),
                windowDistanceKm = windowDistance,
                windowFuelVolumeL = windowFuelL,
                windowSegmentEconomiesL100 = windowSegmentEconomies,
                windowEnergyKwh = windowKwh,
                windowSegmentElectricKwh100 = windowElectricEconomies,
                totalSpendMinor = total,
                costPerKmMinor = EconomyCalculator.costPerKmMinor(total, windowDistance),
                energyCostPerKmMinor = EnergyCostCalculator.totalCostPerKmMinor(purchases, windowDistance),
                energyCostShares = EnergyCostCalculator.breakdown(purchases, windowDistance),
                buckets = buckets,
                trend = trend,
                recent = EntryCards.build(vehicle, fuel, charge, expenses).take(8),
            )
        }

    fun selectVehicle(id: Long) = viewModelScope.launch { selection.select(id) }

    fun setWindowDays(days: Int) = viewModelScope.launch { prefs.setDashboardWindowDays(days) }

    fun delete(item: EntryCardItem) = viewModelScope.launch {
        when (item.kind) {
            EntryKind.FUEL -> entryRepo.deleteFuel(item.id)
            EntryKind.CHARGE -> entryRepo.deleteCharge(item.id)
            EntryKind.EXPENSE -> entryRepo.deleteExpense(item.id)
        }
    }
}

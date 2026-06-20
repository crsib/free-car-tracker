package me.crsib.freecartracker.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.repository.EntryRepository
import me.crsib.freecartracker.data.repository.VehicleRepository
import me.crsib.freecartracker.domain.Money
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.ChargeType
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.domain.model.ExpenseCategory
import me.crsib.freecartracker.domain.model.FuelType
import me.crsib.freecartracker.domain.model.Recurrence
import java.math.BigDecimal

data class EntryFormState(
    val loaded: Boolean = false,
    val saved: Boolean = false,
    val vehicle: Vehicle? = null,
    val allowedKinds: List<EntryKind> = emptyList(),
    val kind: EntryKind = EntryKind.EXPENSE,
    val editId: Long? = null,
    val dateMillis: Long = 0,
    val odometer: String = "",
    val totalCost: String = "",
    val note: String = "",
    // fuel
    val volume: String = "",
    val isFullTank: Boolean = true,
    val station: String = "",
    val fuelType: FuelType = FuelType.PETROL,
    // charge
    val energyKwh: String = "",
    val chargeType: ChargeType = ChargeType.AC,
    val startPct: String = "",
    val endPct: String = "",
    val location: String = "",
    // expense
    val category: ExpenseCategory = ExpenseCategory.SERVICE,
    val vendor: String = "",
    val description: String = "",
    val recurrence: Recurrence = Recurrence.NONE,
    val recurrenceEndMillis: Long? = null,
) {
    val isEditing: Boolean get() = editId != null

    val isValid: Boolean
        get() {
            val cost = totalCost.toBigDecimalOrNull()
            return when (kind) {
                EntryKind.FUEL ->
                    (volume.toDoubleOrNull() ?: 0.0) > 0.0 &&
                        odometer.toDoubleOrNull() != null &&
                        cost != null && cost >= BigDecimal.ZERO
                EntryKind.CHARGE ->
                    (energyKwh.toDoubleOrNull() ?: 0.0) > 0.0 &&
                        cost != null && cost >= BigDecimal.ZERO
                EntryKind.EXPENSE ->
                    cost != null && cost >= BigDecimal.ZERO
            }
        }
}

class EntryEditViewModel(
    private val vehicleRepo: VehicleRepository,
    private val entryRepo: EntryRepository,
    private val vehicleId: Long,
    private val editKind: EntryKind?,
    private val editId: Long?,
) : ViewModel() {

    private val _state = MutableStateFlow(EntryFormState(dateMillis = System.currentTimeMillis()))
    val state: StateFlow<EntryFormState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val vehicle = vehicleRepo.getById(vehicleId) ?: return
        val allowed = buildList {
            if (vehicle.powertrain.usesFuel) add(EntryKind.FUEL)
            if (vehicle.powertrain.usesCharging) add(EntryKind.CHARGE)
            add(EntryKind.EXPENSE)
        }
        val system = vehicle.unitSystem
        var s = _state.value.copy(
            vehicle = vehicle,
            allowedKinds = allowed,
            kind = editKind ?: allowed.first(),
            fuelType = vehicle.fuelType ?: FuelType.PETROL,
            loaded = true,
        )

        if (editKind != null && editId != null) {
            s = when (editKind) {
                EntryKind.FUEL -> entryRepo.getFuel(editId)?.let { e ->
                    s.copy(
                        editId = e.id,
                        dateMillis = e.dateMillis,
                        odometer = num(UnitConversions.kmToDisplay(e.odometerKm, system)),
                        volume = num(UnitConversions.litresToDisplay(e.volumeL, system)),
                        totalCost = money(e.totalCostMinor, vehicle.currency),
                        isFullTank = e.isFullTank,
                        station = e.station.orEmpty(),
                        fuelType = e.fuelType ?: s.fuelType,
                        note = e.note.orEmpty(),
                    )
                } ?: s
                EntryKind.CHARGE -> entryRepo.getCharge(editId)?.let { e ->
                    s.copy(
                        editId = e.id,
                        dateMillis = e.dateMillis,
                        odometer = e.odometerKm?.let { num(UnitConversions.kmToDisplay(it, system)) } ?: "",
                        energyKwh = num(e.energyKwh),
                        totalCost = money(e.totalCostMinor, vehicle.currency),
                        chargeType = e.chargeType,
                        startPct = e.startPct?.toString() ?: "",
                        endPct = e.endPct?.toString() ?: "",
                        location = e.location.orEmpty(),
                        note = e.note.orEmpty(),
                    )
                } ?: s
                EntryKind.EXPENSE -> entryRepo.getExpense(editId)?.let { e ->
                    s.copy(
                        editId = e.id,
                        dateMillis = e.dateMillis,
                        odometer = e.odometerKm?.let { num(UnitConversions.kmToDisplay(it, system)) } ?: "",
                        totalCost = money(e.totalCostMinor, vehicle.currency),
                        category = e.category,
                        vendor = e.vendor.orEmpty(),
                        description = e.description.orEmpty(),
                        recurrence = e.recurrence,
                        recurrenceEndMillis = e.recurrenceEndMillis,
                    )
                } ?: s
            }
        }
        _state.value = s
    }

    fun update(transform: (EntryFormState) -> EntryFormState) = _state.update(transform)

    fun save() = viewModelScope.launch {
        val s = _state.value
        val vehicle = s.vehicle ?: return@launch
        if (!s.isValid) return@launch
        val system = vehicle.unitSystem
        val currency = vehicle.currency
        val costMinor = Money.majorToMinor(s.totalCost.toBigDecimal(), currency)
        val odoKm = s.odometer.toDoubleOrNull()?.let { UnitConversions.displayToKm(it, system) }

        when (s.kind) {
            EntryKind.FUEL -> entryRepo.saveFuel(
                FuelEntry(
                    id = s.editId ?: 0,
                    vehicleId = vehicle.id,
                    dateMillis = s.dateMillis,
                    odometerKm = odoKm ?: 0.0,
                    volumeL = UnitConversions.displayToLitres(s.volume.toDouble(), system),
                    totalCostMinor = costMinor,
                    currency = currency,
                    isFullTank = s.isFullTank,
                    fuelType = s.fuelType,
                    station = s.station.trim().ifBlank { null },
                    note = s.note.trim().ifBlank { null },
                ),
            )
            EntryKind.CHARGE -> entryRepo.saveCharge(
                ChargeEntry(
                    id = s.editId ?: 0,
                    vehicleId = vehicle.id,
                    dateMillis = s.dateMillis,
                    odometerKm = odoKm,
                    energyKwh = s.energyKwh.toDouble(),
                    totalCostMinor = costMinor,
                    currency = currency,
                    chargeType = s.chargeType,
                    startPct = s.startPct.toIntOrNull(),
                    endPct = s.endPct.toIntOrNull(),
                    location = s.location.trim().ifBlank { null },
                    note = s.note.trim().ifBlank { null },
                ),
            )
            EntryKind.EXPENSE -> entryRepo.saveExpense(
                Expense(
                    id = s.editId ?: 0,
                    vehicleId = vehicle.id,
                    dateMillis = s.dateMillis,
                    odometerKm = odoKm,
                    category = s.category,
                    totalCostMinor = costMinor,
                    currency = currency,
                    vendor = s.vendor.trim().ifBlank { null },
                    description = s.description.trim().ifBlank { null },
                    recurrence = s.recurrence,
                    recurrenceEndMillis = s.recurrenceEndMillis.takeIf { s.recurrence != Recurrence.NONE },
                ),
            )
        }
        _state.update { it.copy(saved = true) }
    }

    private fun num(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun money(minor: Long, currency: String): String =
        Money.minorToMajor(minor, currency).toPlainString()
}

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
import java.math.RoundingMode

/** The three linked money fields on the fuel/charge forms; editing two derives the third. */
enum class CostField { QUANTITY, PRICE, TOTAL }

/** Recompute order, most-recently-edited first; the least-recent field is the one derived. */
private val DEFAULT_COST_ORDER = listOf(CostField.QUANTITY, CostField.TOTAL, CostField.PRICE)

data class EntryFormState(
    val loaded: Boolean = false,
    val saved: Boolean = false,
    val vehicle: Vehicle? = null,
    val allowedKinds: List<EntryKind> = emptyList(),
    val kind: EntryKind = EntryKind.EXPENSE,
    val editId: Long? = null,
    val dateMillis: Long = 0,
    val odometer: String = "",
    /** Highest odometer reading on record (canonical km), shown as a hint. Null when none yet. */
    val lastOdometerKm: Double? = null,
    val totalCost: String = "",
    /** Price per quantity unit (per litre/gallon for fuel, per kWh for charge), in major currency. */
    val pricePerUnit: String = "",
    /** Drives which of quantity/price/total is derived when the other two change. */
    val costOrder: List<CostField> = DEFAULT_COST_ORDER,
    val note: String = "",
    // fuel
    val volume: String = "",
    val isFullTank: Boolean = true,
    val station: String = "",
    val fuelType: FuelType = FuelType.PETROL,
    // charge
    val energyKwh: String = "",
    val chargeType: ChargeType = ChargeType.DC,
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
                        odometer.toDoubleOrNull() != null &&
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
        val kind = editKind ?: allowed.first()
        var s = _state.value.copy(
            vehicle = vehicle,
            allowedKinds = allowed,
            kind = kind,
            fuelType = vehicle.fuelType ?: FuelType.PETROL,
            lastOdometerKm = entryRepo.lastOdometerKm(vehicleId),
            loaded = true,
        )

        // Adding a new fuel entry: prefill the cost with the last fill's cost as a convenience.
        if (editId == null && kind == EntryKind.FUEL) {
            entryRepo.latestFuel(vehicleId)?.let { last ->
                s = s.copy(totalCost = money(last.totalCostMinor, vehicle.currency))
            }
        }

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
        // Show the derived price on fuel/charge forms (e.g. when editing an existing entry).
        if ((s.kind == EntryKind.FUEL || s.kind == EntryKind.CHARGE) && s.pricePerUnit.isBlank()) {
            computeCostField(s, CostField.PRICE)?.let { s = s.copy(pricePerUnit = it) }
        }
        _state.value = s
    }

    fun update(transform: (EntryFormState) -> EntryFormState) = _state.update(transform)

    /** Switch entry kind, resetting the per-kind cost linking (quantity field differs per kind). */
    fun setKind(kind: EntryKind) = _state.update {
        it.copy(kind = kind, pricePerUnit = "", costOrder = DEFAULT_COST_ORDER)
    }

    /**
     * Edit one of the three linked money fields. The edited field keeps the raw text the user
     * typed; the least-recently-edited of the other two is recomputed from them (when both parse).
     */
    fun editCostField(field: CostField, raw: String) = _state.update { s ->
        var ns = setCostRaw(s, field, raw)
        if (s.kind != EntryKind.FUEL && s.kind != EntryKind.CHARGE) return@update ns
        val order = listOf(field) + ns.costOrder.filter { it != field }
        ns = ns.copy(costOrder = order)
        val target = order.last()
        computeCostField(ns, target)?.let { ns = setCostRaw(ns, target, it) }
        ns
    }

    private fun quantityOf(s: EntryFormState): String =
        if (s.kind == EntryKind.CHARGE) s.energyKwh else s.volume

    private fun setCostRaw(s: EntryFormState, field: CostField, v: String): EntryFormState =
        when (field) {
            CostField.QUANTITY -> if (s.kind == EntryKind.CHARGE) s.copy(energyKwh = v) else s.copy(volume = v)
            CostField.PRICE -> s.copy(pricePerUnit = v)
            CostField.TOTAL -> s.copy(totalCost = v)
        }

    /** Derive [target] from the other two fields, or null if they aren't both usable. */
    private fun computeCostField(s: EntryFormState, target: CostField): String? {
        val digits = s.vehicle?.let { Money.fractionDigits(it.currency) } ?: 2
        val qty = quantityOf(s).toBigDecimalOrNull()
        val price = s.pricePerUnit.toBigDecimalOrNull()
        val total = s.totalCost.toBigDecimalOrNull()
        return when (target) {
            CostField.TOTAL ->
                if (price != null && qty != null && qty.signum() > 0)
                    (price * qty).setScale(digits, RoundingMode.HALF_UP).toPlainString()
                else null
            CostField.PRICE ->
                if (total != null && qty != null && qty.signum() > 0)
                    total.divide(qty, digits + 1, RoundingMode.HALF_UP).toPlainString()
                else null
            CostField.QUANTITY ->
                if (total != null && price != null && price.signum() > 0)
                    total.divide(price, 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                else null
        }
    }

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

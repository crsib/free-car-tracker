package me.crsib.freecartracker.ui.entries

import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.ChargePoint
import me.crsib.freecartracker.domain.EconomyCalculator
import me.crsib.freecartracker.domain.FuelPoint
import me.crsib.freecartracker.domain.RecurrenceProjector
import me.crsib.freecartracker.domain.UnitConversions
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.domain.model.Recurrence
import me.crsib.freecartracker.ui.util.formatDate
import me.crsib.freecartracker.ui.util.formatNumber
import me.crsib.freecartracker.ui.util.label

/** Which icon an [EntryStat] shows; mapped to an actual vector in the UI layer. */
enum class StatIcon { VOLUME, ENERGY, ODOMETER, CONSUMPTION, LOCATION }

/** One labelled stat on an entry card: an icon and a short value kept on a single line. */
data class EntryStat(val icon: StatIcon, val text: String)

/**
 * A fill-level cue shown next to the amount: a full/partial fuel tank, or a battery level from
 * the charge's end %. Mapped to an actual vector in the UI layer.
 */
enum class AmountBadge {
    TANK_FULL,
    TANK_PARTIAL,
    BATTERY_FULL,
    BATTERY_HIGH,
    BATTERY_MID,
    BATTERY_LOW,
    BATTERY_EMPTY,
    BATTERY_UNKNOWN,
}

/**
 * A ledger entry rendered as a card. Shared by History and the dashboard's recent entries so
 * both show the same information; see [EntryCards.build].
 */
data class EntryCardItem(
    val kind: EntryKind,
    val id: Long,
    val dateMillis: Long,
    val title: String,
    /** Free-text secondary line (used for expenses). */
    val subtitle: String,
    /** Icon+value chips (used for fuel/charge so values never wrap mid-token). */
    val stats: List<EntryStat>,
    /** Fill-level cue shown by the amount (tank fullness / battery level), or null. */
    val amountBadge: AmountBadge?,
    val amountMinor: Long,
    val currency: String,
    /** Lower-cased haystack for the History search box. */
    val search: String,
    /**
     * True for a billing date this app derived from a recurring expense rather than read from
     * the ledger: the same amount, dated at the bill. Derived cards cannot be edited or
     * deleted - [id] points at the entry that defines the rule.
     */
    val projected: Boolean = false,
    /** For a projected card, the date of the entry it repeats. */
    val anchorDateMillis: Long? = null,
)

/** Battery level bucket from a charge's end %, for the [AmountBadge] shown by the amount. */
private fun batteryBadge(endPct: Int?): AmountBadge = when {
    endPct == null -> AmountBadge.BATTERY_UNKNOWN
    endPct >= 90 -> AmountBadge.BATTERY_FULL
    endPct >= 65 -> AmountBadge.BATTERY_HIGH
    endPct >= 40 -> AmountBadge.BATTERY_MID
    endPct >= 15 -> AmountBadge.BATTERY_LOW
    else -> AmountBadge.BATTERY_EMPTY
}

object EntryCards {

    /**
     * Turn the raw ledger into display-ready cards, newest first. Values are formatted in the
     * vehicle's unit system, and fuel/charge entries carry the average consumption of the
     * economy segment they close.
     */
    fun build(
        vehicle: Vehicle,
        fuel: List<FuelEntry>,
        charge: List<ChargeEntry>,
        expenses: List<Expense>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<EntryCardItem> {
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

        return buildList {
            fuel.forEach { e ->
                val odo = formatNumber(UnitConversions.kmToDisplay(e.odometerKm, system), 0)
                val vol = formatNumber(UnitConversions.litresToDisplay(e.volumeL, system), 1)
                val stats = buildList {
                    add(EntryStat(StatIcon.VOLUME, "$vol $vUnit"))
                    add(EntryStat(StatIcon.ODOMETER, "$odo $dUnit"))
                    fuelEcoByOdo[e.odometerKm]
                        ?.let { UnitConversions.fuelEconomyToDisplay(it, system) }
                        ?.let { add(EntryStat(StatIcon.CONSUMPTION, "${formatNumber(it, 1)} $ecoUnit")) }
                }
                add(
                    EntryCardItem(
                        kind = EntryKind.FUEL,
                        id = e.id,
                        dateMillis = e.dateMillis,
                        // Naming the fuel matters on bi-fuel cars, where the per-fuel running
                        // cost on the dashboard only makes sense if entries say which is which.
                        title = e.fuelType?.label() ?: "Fuel",
                        subtitle = "",
                        stats = stats,
                        amountBadge = if (e.isFullTank) AmountBadge.TANK_FULL else AmountBadge.TANK_PARTIAL,
                        amountMinor = e.totalCostMinor,
                        currency = e.currency,
                        search = "fuel ${e.fuelType?.label().orEmpty()} ${e.station.orEmpty()} ${e.note.orEmpty()}",
                    ),
                )
            }
            charge.forEach { e ->
                val stats = buildList {
                    add(EntryStat(StatIcon.ENERGY, "${formatNumber(e.energyKwh, 1)} kWh"))
                    e.location?.takeIf { it.isNotBlank() }?.let { add(EntryStat(StatIcon.LOCATION, it)) }
                    e.odometerKm?.let { chargeEcoByOdo[it] }
                        ?.let { UnitConversions.electricEconomyToDisplay(it, system) }
                        ?.let { add(EntryStat(StatIcon.CONSUMPTION, "${formatNumber(it, 1)} $elUnit")) }
                }
                add(
                    EntryCardItem(
                        kind = EntryKind.CHARGE,
                        id = e.id,
                        dateMillis = e.dateMillis,
                        title = "Charging",
                        subtitle = "",
                        stats = stats,
                        amountBadge = batteryBadge(e.endPct),
                        amountMinor = e.totalCostMinor,
                        currency = e.currency,
                        search = "charge ${e.location.orEmpty()} ${e.note.orEmpty()}",
                    ),
                )
            }
            expenses.forEach { e ->
                fun card(dateMillis: Long, projected: Boolean) = EntryCardItem(
                    kind = EntryKind.EXPENSE,
                    id = e.id,
                    dateMillis = dateMillis,
                    title = e.category.label(),
                    subtitle = listOfNotNull(
                        e.recurrence.takeIf { it != Recurrence.NONE }?.let { rec ->
                            rec.label() + (e.recurrenceEndMillis?.let { " until ${formatDate(it)}" } ?: "")
                        },
                        e.vendor,
                        e.description,
                    ).joinToString(" · "),
                    stats = emptyList(),
                    amountBadge = null,
                    amountMinor = e.totalCostMinor,
                    currency = e.currency,
                    search = "${e.category.label()} ${e.vendor.orEmpty()} ${e.description.orEmpty()}",
                    projected = projected,
                    anchorDateMillis = if (projected) e.dateMillis else null,
                )
                // The entry as logged: its own date is the first billing date.
                add(card(e.dateMillis, projected = false))
                // ...followed by the bills it stands for, up to today. The dashboard counts
                // every one of these in its spend total, so without them the history shows a
                // monthly cost once, years ago, and the total cannot be reconciled with it.
                RecurrenceProjector
                    .occurrenceDatesInWindow(
                        e.dateMillis, e.recurrence, e.dateMillis, nowMillis, e.recurrenceEndMillis,
                    )
                    .forEach { date -> if (date != e.dateMillis) add(card(date, projected = true)) }
            }
        }.sortedByDescending { it.dateMillis }
    }
}

package me.crsib.freecartracker.data.backup

import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.domain.Money
import me.crsib.freecartracker.domain.model.ChargeType
import me.crsib.freecartracker.domain.model.ExpenseCategory
import me.crsib.freecartracker.domain.model.FuelType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parses a [Drivvo](https://www.drivvo.com) CSV export into Car Tracker entities.
 *
 * The export is a single file split into `##Refuelling`, `##Expense` and `##Service` sections,
 * each preceded by its own column-header row and separated by a blank line. Refuelling rows whose
 * fuel is "Electric" become [ChargeEntry]; other fuels become [FuelEntry]. Expense and Service rows
 * both become [Expense], the latter always categorised as service.
 *
 * Drivvo quotes every cell but does **not** escape inner quotes — e.g. `"Азс № 154 "железноводская""`
 * — so [tokenize] is lenient: a quote only closes a field when it is followed by a comma or the end
 * of the line; any other quote is treated as literal text.
 *
 * Amounts are taken verbatim and interpreted in the target vehicle's [currency] (the file states no
 * currency of its own). Timestamps are parsed in [zone].
 */
class DrivvoCsvParser(private val zone: ZoneId = ZoneId.systemDefault()) {

    data class Result(
        val fuel: List<FuelEntry> = emptyList(),
        val charges: List<ChargeEntry> = emptyList(),
        val expenses: List<Expense> = emptyList(),
    ) {
        val total: Int get() = fuel.size + charges.size + expenses.size
    }

    private enum class Section { REFUELLING, EXPENSE, SERVICE }

    fun parse(text: String, vehicleId: Long, currency: String): Result {
        val fuel = mutableListOf<FuelEntry>()
        val charges = mutableListOf<ChargeEntry>()
        val expenses = mutableListOf<Expense>()

        var section: Section? = null
        var expectHeader = false

        for (rawLine in text.removePrefix("﻿").lineSequence()) {
            val line = rawLine.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            if (line.startsWith("##")) {
                section = sectionOf(line)
                expectHeader = section != null
                continue
            }
            if (expectHeader) {
                expectHeader = false // the column-header row right after a section heading
                continue
            }
            val cells = tokenize(rawLine)
            when (section) {
                Section.REFUELLING -> parseRefuel(cells, vehicleId, currency, fuel, charges)
                Section.EXPENSE -> parseExpense(cells, vehicleId, currency)?.let(expenses::add)
                Section.SERVICE -> parseService(cells, vehicleId, currency)?.let(expenses::add)
                null -> {} // preamble before the first known section: ignore
            }
        }
        return Result(fuel, charges, expenses)
    }

    private fun sectionOf(heading: String): Section? {
        val h = heading.removePrefix("##").trim().lowercase()
        return when {
            h.startsWith("refuel") -> Section.REFUELLING
            h.startsWith("expense") -> Section.EXPENSE
            h.startsWith("service") -> Section.SERVICE
            else -> null
        }
    }

    private fun parseRefuel(
        cells: List<String>,
        vehicleId: Long,
        currency: String,
        fuel: MutableList<FuelEntry>,
        charges: MutableList<ChargeEntry>,
    ) {
        val date = parseDate(cells.cell(1)) ?: return
        val fuelName = cells.cell(2)
        val costMinor = parseMoney(cells.cell(4), currency)
        val volume = cells.cell(5).toDoubleOrNull() ?: 0.0
        val odometer = cells.cell(0).toDoubleOrNull()
        val full = cells.cell(6).isYes()
        val place = cells.cell(19).cleanOrNull()
        val note = note(cells.cell(21), cells.cell(23))

        if (isElectric(fuelName)) {
            charges += ChargeEntry(
                vehicleId = vehicleId,
                dateMillis = date,
                odometerKm = odometer,
                energyKwh = volume,
                totalCostMinor = costMinor,
                currency = currency,
                chargeType = ChargeType.AC,
                location = place,
                note = note,
            )
        } else {
            fuel += FuelEntry(
                vehicleId = vehicleId,
                dateMillis = date,
                odometerKm = odometer ?: 0.0,
                volumeL = volume,
                totalCostMinor = costMinor,
                currency = currency,
                isFullTank = full,
                fuelType = mapFuelType(fuelName),
                station = place,
                note = note,
            )
        }
    }

    private fun parseExpense(cells: List<String>, vehicleId: Long, currency: String): Expense? {
        val date = parseDate(cells.cell(1)) ?: return null
        val label = cells.cell(3).cleanOrNull()
        return Expense(
            vehicleId = vehicleId,
            dateMillis = date,
            odometerKm = cells.cell(0).toDoubleOrNull(),
            category = mapExpenseCategory(label),
            totalCostMinor = parseMoney(cells.cell(2), currency),
            currency = currency,
            vendor = cells.cell(4).cleanOrNull(),
            description = note(label, cells.cell(6), cells.cell(8)),
        )
    }

    private fun parseService(cells: List<String>, vehicleId: Long, currency: String): Expense? {
        val date = parseDate(cells.cell(1)) ?: return null
        return Expense(
            vehicleId = vehicleId,
            dateMillis = date,
            odometerKm = cells.cell(0).toDoubleOrNull(),
            category = ExpenseCategory.SERVICE,
            totalCostMinor = parseMoney(cells.cell(2), currency),
            currency = currency,
            vendor = cells.cell(4).cleanOrNull(),
            description = note(cells.cell(3), cells.cell(7)),
        )
    }

    private fun parseMoney(raw: String, currency: String): Long {
        val amount = raw.trim().toBigDecimalOrNull() ?: return 0L
        return Money.majorToMinor(amount, currency)
    }

    private fun parseDate(raw: String): Long? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        runCatching { LocalDateTime.parse(v, DATE_TIME).atZone(zone).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        // Fall back to a date-only value (some Drivvo locales omit the time).
        return runCatching {
            LocalDate.parse(v.take(10)).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun isElectric(fuelName: String): Boolean {
        val t = fuelName.lowercase()
        return "electric" in t || "электр" in t
    }

    private fun mapFuelType(fuelName: String): FuelType {
        val t = fuelName.lowercase()
        return when {
            "diesel" in t || "дизел" in t -> FuelType.DIESEL
            "lpg" in t || "gpl" in t -> FuelType.LPG
            "cng" in t || "gnv" in t -> FuelType.CNG
            "ethanol" in t || "alcohol" in t || "этанол" in t || "этил" in t -> FuelType.ETHANOL
            "gasolin" in t || "petrol" in t || "бензин" in t -> FuelType.PETROL
            t.isBlank() -> FuelType.PETROL
            else -> FuelType.OTHER
        }
    }

    private fun mapExpenseCategory(label: String?): ExpenseCategory {
        val t = label?.lowercase() ?: return ExpenseCategory.OTHER
        return when {
            "financ" in t || "loan" in t || "credit" in t || "кредит" in t || "лизинг" in t -> ExpenseCategory.FINANCING
            "insur" in t || "страхов" in t || "осаго" in t || "каско" in t -> ExpenseCategory.INSURANCE
            "wash" in t || "мойк" in t -> ExpenseCategory.CAR_WASH
            "park" in t || "парков" in t -> ExpenseCategory.PARKING
            "toll" in t -> ExpenseCategory.TOLL
            "tax" in t || "налог" in t -> ExpenseCategory.TAX
            "accessor" in t || "аксессуар" in t -> ExpenseCategory.ACCESSORY
            "service" in t || "обслуж" in t || "ремонт" in t -> ExpenseCategory.SERVICE
            else -> ExpenseCategory.OTHER
        }
    }

    /** Joins the non-blank parts into a single human-readable note, or null when all are blank. */
    private fun note(vararg parts: String?): String? =
        parts.mapNotNull { it?.cleanOrNull() }.joinToString(" — ").ifEmpty { null }

    private fun String.isYes(): Boolean = trim().lowercase().let { it == "yes" || it == "да" || it == "true" }

    private fun String.cleanOrNull(): String? = trim().ifEmpty { null }

    /** Safe positional access — out-of-range or null cells read as empty. */
    private fun List<String>.cell(index: Int): String = getOrNull(index).orEmpty()

    companion object {
        private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * Splits one CSV record into cells, tolerating Drivvo's unescaped inner quotes. A quote
         * opens a field only at its start. Inside a quoted field a quote is the *closing* quote only
         * when no further quote appears before the next comma or end-of-line — i.e. it is the last
         * quote before the delimiter; any earlier quote is literal text (so `"Азс № 154 "x""` keeps
         * its inner quotes), and any junk between the closing quote and the comma (e.g. the header's
         * ` 2`/` 3` suffixes) is discarded.
         */
        fun tokenize(rawLine: String): List<String> {
            val s = rawLine.removeSuffix("\r")
            val out = ArrayList<String>()
            val field = StringBuilder()
            var i = 0
            val len = s.length
            while (i <= len) {
                if (i == len) {
                    out.add(field.toString())
                    break
                }
                val c = s[i]
                when {
                    c == ',' -> {
                        out.add(field.toString())
                        field.setLength(0)
                        i++
                    }
                    c == '"' && field.isEmpty() -> {
                        i++
                        while (i < len) {
                            val ch = s[i]
                            if (ch == '"' && !hasQuoteBeforeComma(s, i + 1)) {
                                // Closing quote: skip ahead to the delimiter, dropping any junk.
                                i++
                                while (i < len && s[i] != ',') i++
                                break
                            }
                            field.append(ch)
                            i++
                        }
                    }
                    else -> {
                        field.append(c)
                        i++
                    }
                }
            }
            return out
        }

        /** True when another `"` appears before the next comma or end-of-line, scanning from [from]. */
        private fun hasQuoteBeforeComma(s: String, from: Int): Boolean {
            var j = from
            while (j < s.length && s[j] != ',') {
                if (s[j] == '"') return true
                j++
            }
            return false
        }
    }
}

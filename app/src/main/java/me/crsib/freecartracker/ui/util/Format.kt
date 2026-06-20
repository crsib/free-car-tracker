package me.crsib.freecartracker.ui.util

import me.crsib.freecartracker.domain.Money
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

private val monthFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy").withZone(ZoneId.systemDefault())

fun formatDate(epochMillis: Long): String =
    dateFormatter.format(Instant.ofEpochMilli(epochMillis))

fun formatMonthYear(epochMillis: Long): String =
    monthFormatter.format(Instant.ofEpochMilli(epochMillis))

/** Format minor-unit money with the currency's symbol and fraction digits. */
fun formatMoney(minor: Long, currencyCode: String): String {
    val nf = NumberFormat.getCurrencyInstance(Locale.getDefault())
    runCatching { nf.currency = Currency.getInstance(currencyCode) }
    return nf.format(Money.minorToMajor(minor, currencyCode))
}

/** Short label for a dashboard window length, e.g. "30d" or "All time". */
fun windowLabel(days: Int): String = if (days <= 0) "All time" else "${days}d"

/** Longer label for a window length used in menus, e.g. "Last 30 days" or "All time". */
fun windowMenuLabel(days: Int): String = if (days <= 0) "All time" else "Last $days days"

/** Format a number with up to [decimals] fraction digits, trimming trailing zeros. */
fun formatNumber(value: Double, decimals: Int = 1): String {
    val nf = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = decimals
        minimumFractionDigits = 0
    }
    return nf.format(value)
}

package me.crsib.freecartracker.ui.util

import me.crsib.freecartracker.domain.Money
import java.math.BigDecimal
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

/**
 * Format a fractional minor-unit amount (a cost per km, say) as money. A per-distance cost is
 * often a fraction of a cent, so small amounts keep extra fraction digits rather than rounding
 * away to nothing.
 */
fun formatMoneyPrecise(minor: Double, currencyCode: String): String {
    val digits = Money.fractionDigits(currencyCode)
    // Widen by magnitude, so every amount keeps roughly three significant digits: enough to
    // separate a cheap kilometre from an expensive one without inventing precision.
    val magnitude = kotlin.math.abs(minor)
    val extra = when {
        magnitude < 10.0 -> 2
        magnitude < 100.0 -> 1
        else -> 0
    }
    val nf = NumberFormat.getCurrencyInstance(Locale.getDefault())
    // Setting the currency resets the fraction digits to its default, so widen them after.
    runCatching { nf.currency = Currency.getInstance(currencyCode) }
    nf.maximumFractionDigits = digits + extra
    nf.minimumFractionDigits = digits
    return nf.format(BigDecimal(minor).movePointLeft(digits))
}

/** Short label for a dashboard window length, e.g. "30d" or "All time". */
fun windowLabel(days: Int): String = if (days <= 0) "All time" else "${days}d"

/** Longer label for a window length used in menus, e.g. "Last 30 days" or "All time". */
fun windowMenuLabel(days: Int): String = if (days <= 0) "All time" else "Last $days days"

/** Format a duration in seconds as H:MM:SS (or M:SS under an hour). */
fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.getDefault(), "%d:%02d", m, sec)
}

/** Format a number with up to [decimals] fraction digits, trimming trailing zeros. */
fun formatNumber(value: Double, decimals: Int = 1): String {
    val nf = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = decimals
        minimumFractionDigits = 0
    }
    return nf.format(value)
}

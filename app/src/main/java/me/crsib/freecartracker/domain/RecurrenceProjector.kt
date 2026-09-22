package me.crsib.freecartracker.domain

import me.crsib.freecartracker.domain.model.Recurrence
import java.time.Instant
import java.time.ZoneId

/**
 * Expands a recurring cost into discrete billing occurrences. A recurring expense is logged
 * once (its date is the anchor / first bill); the same amount is then billed every period
 * from that date onward. This counts how many billing dates fall inside a window so the
 * dashboard can show what was actually billed in the last N days.
 */
object RecurrenceProjector {

    private const val GUARD = 100_000

    /**
     * Number of billing dates in `[windowStart, windowEnd]` for a charge anchored at
     * [startMillis]. See [occurrenceDatesInWindow] for the rules; this is its size, kept as a
     * separate call because the dashboard only ever needs the count.
     */
    fun occurrencesInWindow(
        startMillis: Long,
        recurrence: Recurrence,
        windowStart: Long,
        windowEnd: Long,
        endMillis: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int = occurrenceDatesInWindow(startMillis, recurrence, windowStart, windowEnd, endMillis, zone).size

    /**
     * The billing dates in `[windowStart, windowEnd]` for a charge anchored at [startMillis],
     * in order. A one-off ([Recurrence.NONE]) yields its own date if that is in the window.
     * Occurrences before the window (e.g. a loan started months ago) are skipped. When
     * [endMillis] is non-null, no bill after that date is counted, so a fixed-term loan stops
     * on its final payment; null means the cost recurs indefinitely.
     *
     * The anchor is itself the first bill, so it appears in the result whenever it is in
     * range - callers that show the logged entry separately must skip it to avoid listing it
     * twice.
     */
    fun occurrenceDatesInWindow(
        startMillis: Long,
        recurrence: Recurrence,
        windowStart: Long,
        windowEnd: Long,
        endMillis: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Long> {
        if (windowEnd < windowStart) return emptyList()
        // Bills never occur after the recurrence end, so clamp the window's upper bound.
        val upper = if (endMillis != null) minOf(windowEnd, endMillis) else windowEnd
        if (upper < windowStart) return emptyList()
        if (recurrence == Recurrence.NONE) {
            return if (startMillis in windowStart..upper) listOf(startMillis) else emptyList()
        }
        var occ = Instant.ofEpochMilli(startMillis).atZone(zone)
        val dates = mutableListOf<Long>()
        var guard = 0
        while (guard++ < GUARD) {
            val ms = occ.toInstant().toEpochMilli()
            if (ms > upper) break
            if (ms >= windowStart) dates += ms
            occ = when (recurrence) {
                Recurrence.WEEKLY -> occ.plusWeeks(1)
                Recurrence.MONTHLY -> occ.plusMonths(1)
                Recurrence.QUARTERLY -> occ.plusMonths(3)
                Recurrence.YEARLY -> occ.plusYears(1)
                Recurrence.NONE -> break
            }
        }
        return dates
    }
}

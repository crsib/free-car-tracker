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
     * [startMillis] recurring with [recurrence]. A one-off ([Recurrence.NONE]) counts once if
     * its own date is in the window. Occurrences before the window (e.g. a loan started months
     * ago) are skipped. When [endMillis] is non-null, no bill after that date is counted, so a
     * fixed-term loan stops on its final payment; null means the cost recurs indefinitely.
     */
    fun occurrencesInWindow(
        startMillis: Long,
        recurrence: Recurrence,
        windowStart: Long,
        windowEnd: Long,
        endMillis: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (windowEnd < windowStart) return 0
        // Bills never occur after the recurrence end, so clamp the window's upper bound.
        val upper = if (endMillis != null) minOf(windowEnd, endMillis) else windowEnd
        if (upper < windowStart) return 0
        if (recurrence == Recurrence.NONE) {
            return if (startMillis in windowStart..upper) 1 else 0
        }
        var occ = Instant.ofEpochMilli(startMillis).atZone(zone)
        var count = 0
        var guard = 0
        while (guard++ < GUARD) {
            val ms = occ.toInstant().toEpochMilli()
            if (ms > upper) break
            if (ms >= windowStart) count++
            occ = when (recurrence) {
                Recurrence.WEEKLY -> occ.plusWeeks(1)
                Recurrence.MONTHLY -> occ.plusMonths(1)
                Recurrence.QUARTERLY -> occ.plusMonths(3)
                Recurrence.YEARLY -> occ.plusYears(1)
                Recurrence.NONE -> break
            }
        }
        return count
    }
}

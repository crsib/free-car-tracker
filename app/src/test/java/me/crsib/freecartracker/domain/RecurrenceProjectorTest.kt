package me.crsib.freecartracker.domain

import me.crsib.freecartracker.domain.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class RecurrenceProjectorTest {

    private val utc = ZoneOffset.UTC

    private fun millis(date: String): Long =
        LocalDate.parse(date).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun count(
        start: String,
        recurrence: Recurrence,
        from: String,
        to: String,
        end: String? = null,
    ): Int =
        RecurrenceProjector.occurrencesInWindow(
            startMillis = millis(start),
            recurrence = recurrence,
            windowStart = millis(from),
            windowEnd = millis(to),
            endMillis = end?.let { millis(it) },
            zone = utc,
        )

    @Test
    fun `one-off counts once when its date is inside the window`() {
        assertEquals(1, count("2026-06-10", Recurrence.NONE, "2026-06-01", "2026-06-30"))
        assertEquals(0, count("2026-05-10", Recurrence.NONE, "2026-06-01", "2026-06-30"))
    }

    @Test
    fun `monthly financing started months ago still bills once in a 30-day window`() {
        // Loan anchored Jan 15; a ~30-day window in June should capture exactly one bill.
        assertEquals(1, count("2026-01-15", Recurrence.MONTHLY, "2026-05-20", "2026-06-19"))
    }

    @Test
    fun `monthly bills counted across a 90-day window`() {
        // Anchored Jan 15; window Mar 20 - Jun 19 covers Apr 15, May 15, Jun 15 = 3 bills.
        assertEquals(3, count("2026-01-15", Recurrence.MONTHLY, "2026-03-20", "2026-06-19"))
    }

    @Test
    fun `just-logged monthly payment counts once`() {
        // Logged today, window is the last 30 days ending today -> the anchor bill counts.
        assertEquals(1, count("2026-06-19", Recurrence.MONTHLY, "2026-05-20", "2026-06-19"))
    }

    @Test
    fun `weekly bills counted in a 30-day window`() {
        // Anchored Jun 1; window Jun 1 - Jun 30 -> Jun 1, 8, 15, 22, 29 = 5.
        assertEquals(5, count("2026-06-01", Recurrence.WEEKLY, "2026-06-01", "2026-06-30"))
    }

    @Test
    fun `yearly bill counts once when anniversary falls in window`() {
        assertEquals(1, count("2024-06-10", Recurrence.YEARLY, "2026-06-01", "2026-06-30"))
        assertEquals(0, count("2024-07-10", Recurrence.YEARLY, "2026-06-01", "2026-06-30"))
    }

    @Test
    fun `quarterly bills step by three months`() {
        // Anchored Jan 10; quarters land Jan/Apr/Jul/Oct. Window covering Apr 10 -> 1.
        assertEquals(1, count("2026-01-10", Recurrence.QUARTERLY, "2026-04-01", "2026-04-30"))
    }

    @Test
    fun `end date stops a finished loan from billing`() {
        // Loan ended in April; a June window should count nothing.
        assertEquals(0, count("2026-01-15", Recurrence.MONTHLY, "2026-05-20", "2026-06-19", end = "2026-04-15"))
    }

    @Test
    fun `end date inside the window counts the final bill but no more`() {
        // Anchored Jan 15, ends May 15. Window Mar 20 - Jun 19 covers Apr 15 and May 15 = 2.
        assertEquals(2, count("2026-01-15", Recurrence.MONTHLY, "2026-03-20", "2026-06-19", end = "2026-05-15"))
    }
    @Test
    fun `the dates behind a count are the billing dates themselves`() {
        val dates = RecurrenceProjector.occurrenceDatesInWindow(
            startMillis = millis("2026-01-15"),
            recurrence = Recurrence.MONTHLY,
            windowStart = millis("2026-04-01"),
            windowEnd = millis("2026-06-30"),
            zone = utc,
        )
        assertEquals(listOf(millis("2026-04-15"), millis("2026-05-15"), millis("2026-06-15")), dates)
        // The count is just how many there are - one walk, two callers.
        assertEquals(dates.size, count("2026-01-15", Recurrence.MONTHLY, "2026-04-01", "2026-06-30"))
    }
}

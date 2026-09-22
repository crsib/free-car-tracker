package me.crsib.freecartracker.ui.entries

import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.model.ExpenseCategory
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EntryCardsTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun millis(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private val car = Vehicle(id = 1, name = "Car", powertrain = Powertrain.ICE, currency = "RUB", createdAt = 0)

    private fun expense(
        date: String,
        recurrence: Recurrence,
        end: String? = null,
    ) = Expense(
        id = 7,
        vehicleId = 1,
        dateMillis = millis(date),
        category = ExpenseCategory.INSURANCE,
        totalCostMinor = 4_500_000,
        currency = "RUB",
        recurrence = recurrence,
        recurrenceEndMillis = end?.let { millis(it) },
    )

    private fun cards(expense: Expense, now: String) =
        EntryCards.build(car, emptyList(), emptyList(), listOf(expense), millis(now))

    @Test
    fun `a recurring expense is followed by the bills it stands for`() {
        val cards = cards(expense("2026-01-15", Recurrence.MONTHLY), now = "2026-04-20")

        // Logged in January, then billed in February, March and April.
        assertEquals(4, cards.size)
        val logged = cards.filter { !it.projected }
        assertEquals(1, logged.size)
        assertEquals(millis("2026-01-15"), logged.single().dateMillis)

        val projected = cards.filter { it.projected }
        assertEquals(
            listOf(millis("2026-04-15"), millis("2026-03-15"), millis("2026-02-15")),
            projected.map { it.dateMillis },
        )
        // Every repeat carries the full amount and points back at the entry that defines it.
        assertTrue(projected.all { it.amountMinor == 4_500_000L })
        assertTrue(projected.all { it.anchorDateMillis == millis("2026-01-15") })
    }

    @Test
    fun `nothing is billed after the recurrence ends`() {
        val cards = cards(expense("2026-01-15", Recurrence.MONTHLY, end = "2026-03-20"), now = "2026-08-01")

        assertEquals(listOf(millis("2026-03-15"), millis("2026-02-15")), cards.filter { it.projected }.map { it.dateMillis })
    }

    @Test
    fun `nothing is billed beyond today`() {
        val cards = cards(expense("2026-01-15", Recurrence.MONTHLY), now = "2026-03-14")

        // March's bill is a day away, so only February's has happened.
        assertEquals(listOf(millis("2026-02-15")), cards.filter { it.projected }.map { it.dateMillis })
    }

    @Test
    fun `a one-off is never repeated`() {
        val cards = cards(expense("2026-01-15", Recurrence.NONE), now = "2027-01-01")

        assertEquals(1, cards.size)
        assertFalse(cards.single().projected)
    }
}

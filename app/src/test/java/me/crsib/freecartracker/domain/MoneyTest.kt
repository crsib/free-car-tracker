package me.crsib.freecartracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {

    @Test
    fun `major to minor uses currency fraction digits`() {
        assertEquals(4250L, Money.majorToMinor(BigDecimal("42.50"), "USD"))
        assertEquals(4250L, Money.majorToMinor(BigDecimal("42.495"), "USD")) // HALF_UP
    }

    @Test
    fun `zero-decimal currency keeps whole units`() {
        // JPY has 0 fraction digits
        assertEquals(500L, Money.majorToMinor(BigDecimal("500"), "JPY"))
    }

    @Test
    fun `minor to major restores amount`() {
        assertEquals(BigDecimal("42.50"), Money.minorToMajor(4250L, "USD"))
    }

    @Test
    fun `price per unit divides cost by quantity`() {
        // 50.00 over 40 L = 1.250 per L (fractionDigits+1 scale)
        assertEquals(BigDecimal("1.250"), Money.pricePerUnit(5000L, 40.0, "USD"))
    }
}

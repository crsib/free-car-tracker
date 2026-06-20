package me.crsib.freecartracker.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/** Money helpers operating on integer minor units to avoid floating-point drift. */
object Money {

    fun fractionDigits(currencyCode: String): Int =
        runCatching { Currency.getInstance(currencyCode).defaultFractionDigits }
            .getOrDefault(2)
            .coerceAtLeast(0)

    /** Parse a user-entered major-unit amount (e.g. "42.50") into minor units. */
    fun majorToMinor(amount: BigDecimal, currencyCode: String): Long {
        val digits = fractionDigits(currencyCode)
        return amount.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    fun minorToMajor(minor: Long, currencyCode: String): BigDecimal {
        val digits = fractionDigits(currencyCode)
        return BigDecimal(minor).movePointLeft(digits)
    }

    /** Price per unit (e.g. per litre) in major units, for display. */
    fun pricePerUnit(totalMinor: Long, quantity: Double, currencyCode: String): BigDecimal? {
        if (quantity <= 0.0) return null
        return minorToMajor(totalMinor, currencyCode)
            .divide(BigDecimal(quantity), fractionDigits(currencyCode) + 1, RoundingMode.HALF_UP)
    }
}

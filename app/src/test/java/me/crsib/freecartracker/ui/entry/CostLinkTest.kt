package me.crsib.freecartracker.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Test

class CostLinkTest {

    /** Mutable helper that threads the derived-field role between edits, like the ViewModel does. */
    private class Form(var values: CostValues, var derived: CostField, val digits: Int = 2) {
        fun edit(field: CostField, raw: String): Form {
            val (v, d) = CostLink.apply(values, derived, field, raw, digits)
            values = v; derived = d
            return this
        }
    }

    private fun prefilled(price: String) =
        Form(CostValues(quantity = "", price = price, total = ""), CostField.TOTAL)

    private fun blank() = Form(CostValues("", "", ""), CostField.PRICE)

    @Test
    fun `no price - typing quantity then total derives price`() {
        val f = blank().edit(CostField.QUANTITY, "40").edit(CostField.TOTAL, "70")
        assertEquals("1.750", f.values.price)
    }

    @Test
    fun `no price - multi-digit total keeps deriving price, quantity preserved`() {
        // "70" arrives as "7" then "70"; price must track total/qty, not flip to recompute quantity.
        val f = blank().edit(CostField.QUANTITY, "40").edit(CostField.TOTAL, "7").edit(CostField.TOTAL, "70")
        assertEquals("1.750", f.values.price)
        assertEquals("40", f.values.quantity)
    }

    @Test
    fun `prefilled price - multi-digit quantity keeps deriving total, price preserved`() {
        val f = prefilled("1.500").edit(CostField.QUANTITY, "4").edit(CostField.QUANTITY, "40")
        assertEquals("60.00", f.values.total)
        assertEquals("1.500", f.values.price)
    }

    @Test
    fun `prefilled price plus total derives quantity`() {
        val f = prefilled("1.5").edit(CostField.TOTAL, "90")
        assertEquals("60", f.values.quantity)
        assertEquals("1.5", f.values.price)
    }

    @Test
    fun `editing price recomputes total keeping quantity`() {
        val f = Form(CostValues("40", "2", "80"), CostField.TOTAL).edit(CostField.PRICE, "2.5")
        assertEquals("100.00", f.values.total)
        assertEquals("40", f.values.quantity)
    }

    @Test
    fun `clearing total never corrupts the prefilled price, then quantity recomputes total`() {
        // The exact reported flow: derived total, clear it digit by digit, then change quantity.
        val f = prefilled("1.500").edit(CostField.QUANTITY, "40")
        assertEquals("60.00", f.values.total)
        for (partial in listOf("60.0", "60.", "60", "6", "")) {
            f.edit(CostField.TOTAL, partial)
            assertEquals("1.500", f.values.price) // price stays put throughout
        }
        assertEquals("", f.values.total)
        f.edit(CostField.QUANTITY, "50")
        assertEquals("75.00", f.values.total)
        assertEquals("1.500", f.values.price)
    }

    @Test
    fun `partial input leaves dependents untouched`() {
        val f = prefilled("2").edit(CostField.QUANTITY, ".")
        assertEquals("", f.values.total)
    }

    @Test
    fun `zero-decimal currency rounds total to whole units`() {
        val f = Form(CostValues("", "150", ""), CostField.TOTAL, digits = 0).edit(CostField.QUANTITY, "40")
        assertEquals("6000", f.values.total)
    }
}

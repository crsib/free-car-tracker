package me.crsib.freecartracker.ui.entry

import java.math.RoundingMode

/** The three linked values on the fuel/charge forms, related by total = price * quantity. */
data class CostValues(val quantity: String, val price: String, val total: String)

/**
 * Pure string math for the three-way linked quantity / price / total fields. Two of the fields are
 * user inputs and the third is the derived output; editing an input recomputes the output.
 *
 * The output role is sticky: it only changes when the user edits the *current* output field (taking
 * it over), at which point one of the former inputs becomes the new output. This keeps routing
 * stable across multi-digit entry — typing "40" into an input always recomputes the same output,
 * rather than flipping targets after the first digit because an intermediate value appeared.
 *
 * Price is preferred as an input (the rate the user knows), so taking over the total recomputes the
 * quantity and vice-versa; the price is only the output when there is no price to anchor on.
 * Kept free of Android/state so it can be unit-tested directly.
 */
object CostLink {

    /**
     * Apply a user edit of [edited] to [raw] given the current [derived] (output) field. Returns the
     * updated values and the output field to use going forward.
     */
    fun apply(
        values: CostValues,
        derived: CostField,
        edited: CostField,
        raw: String,
        currencyDigits: Int,
    ): Pair<CostValues, CostField> {
        val updated = replace(values, edited, raw)
        // Editing the current output takes it over; a former input becomes the new output.
        val output = if (edited != derived) derived else reassign(edited, updated)
        val computed = derive(output, updated, currencyDigits)
        val result = if (computed != null) replace(updated, output, computed) else updated
        return result to output
    }

    /** When the user takes over the output field [edited], choose which former input becomes output. */
    private fun reassign(edited: CostField, v: CostValues): CostField {
        val hasPrice = v.price.toBigDecimalOrNull() != null
        val hasQuantity = v.quantity.toBigDecimalOrNull() != null
        return when (edited) {
            // Keep price as an input when we have one; otherwise price is the derived result.
            CostField.QUANTITY -> if (hasPrice) CostField.TOTAL else CostField.PRICE
            CostField.TOTAL -> if (hasPrice) CostField.QUANTITY else CostField.PRICE
            CostField.PRICE -> if (hasQuantity) CostField.TOTAL else CostField.QUANTITY
        }
    }

    /** Derive [target] from the other two values, or null if they aren't both usable. */
    fun derive(target: CostField, values: CostValues, currencyDigits: Int): String? {
        val qty = values.quantity.toBigDecimalOrNull()
        val price = values.price.toBigDecimalOrNull()
        val total = values.total.toBigDecimalOrNull()
        return when (target) {
            CostField.TOTAL ->
                if (price != null && qty != null && qty.signum() > 0)
                    (price * qty).setScale(currencyDigits, RoundingMode.HALF_UP).toPlainString()
                else null
            CostField.PRICE ->
                if (total != null && qty != null && qty.signum() > 0)
                    total.divide(qty, currencyDigits + 1, RoundingMode.HALF_UP).toPlainString()
                else null
            CostField.QUANTITY ->
                if (total != null && price != null && price.signum() > 0)
                    total.divide(price, 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                else null
        }
    }

    private fun replace(v: CostValues, f: CostField, value: String): CostValues = when (f) {
        CostField.QUANTITY -> v.copy(quantity = value)
        CostField.PRICE -> v.copy(price = value)
        CostField.TOTAL -> v.copy(total = value)
    }
}

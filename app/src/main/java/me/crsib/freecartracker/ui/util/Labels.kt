package me.crsib.freecartracker.ui.util

import me.crsib.freecartracker.domain.model.ChargeType
import me.crsib.freecartracker.domain.model.ExpenseCategory
import me.crsib.freecartracker.domain.model.FuelType
import me.crsib.freecartracker.domain.model.Powertrain
import me.crsib.freecartracker.domain.model.Recurrence
import me.crsib.freecartracker.domain.model.UnitSystem

fun Powertrain.label(): String = when (this) {
    Powertrain.ICE -> "Petrol / Diesel (ICE)"
    Powertrain.HYBRID -> "Hybrid (self-charging)"
    Powertrain.REV -> "Plug-in hybrid / REV"
    Powertrain.EV -> "Electric (EV)"
}

fun FuelType.label(): String = when (this) {
    FuelType.PETROL -> "Petrol"
    FuelType.DIESEL -> "Diesel"
    FuelType.LPG -> "LPG"
    FuelType.CNG -> "CNG"
    FuelType.ETHANOL -> "Ethanol"
    FuelType.OTHER -> "Other"
}

fun ChargeType.label(): String = when (this) {
    ChargeType.AC -> "AC (slow)"
    ChargeType.DC -> "DC (fast)"
}

fun UnitSystem.label(): String = when (this) {
    UnitSystem.METRIC -> "Metric (km, L)"
    UnitSystem.IMPERIAL -> "Imperial (mi, gal)"
}

fun ExpenseCategory.label(): String = when (this) {
    ExpenseCategory.SERVICE -> "Service"
    ExpenseCategory.CAR_WASH -> "Car wash"
    ExpenseCategory.ACCESSORY -> "Accessory"
    ExpenseCategory.FINANCING -> "Financing"
    ExpenseCategory.INSURANCE -> "Insurance"
    ExpenseCategory.TAX -> "Tax / registration"
    ExpenseCategory.PARKING -> "Parking"
    ExpenseCategory.TOLL -> "Toll"
    ExpenseCategory.OTHER -> "Other"
}

fun Recurrence.label(): String = when (this) {
    Recurrence.NONE -> "One-off"
    Recurrence.WEEKLY -> "Weekly"
    Recurrence.MONTHLY -> "Monthly"
    Recurrence.QUARTERLY -> "Quarterly"
    Recurrence.YEARLY -> "Yearly"
}

/** Bucket key from cost_view to a display label (fuel/charge or an expense category name). */
fun costBucketLabel(bucket: String): String = when (bucket) {
    "FUEL" -> "Fuel"
    "CHARGE" -> "Charging"
    else -> runCatching { ExpenseCategory.valueOf(bucket).label() }.getOrDefault(bucket)
}

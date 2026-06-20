package me.crsib.freecartracker.data.entity

import androidx.room.DatabaseView

/**
 * Read-only union of every cost-bearing row, so total-spend and cost-per-distance
 * aggregations need no joins. [category] is non-null only for expense rows.
 */
@DatabaseView(
    viewName = "cost_view",
    value = """
        SELECT vehicleId, dateMillis, totalCostMinor, 'FUEL' AS sourceType, NULL AS category
        FROM fuel_entries
        UNION ALL
        SELECT vehicleId, dateMillis, totalCostMinor, 'CHARGE' AS sourceType, NULL AS category
        FROM charge_entries
        UNION ALL
        SELECT vehicleId, dateMillis, totalCostMinor, 'EXPENSE' AS sourceType, category AS category
        FROM expenses
    """,
)
data class CostView(
    val vehicleId: Long,
    val dateMillis: Long,
    val totalCostMinor: Long,
    val sourceType: String,
    val category: String?,
)

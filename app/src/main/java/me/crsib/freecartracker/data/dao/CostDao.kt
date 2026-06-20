package me.crsib.freecartracker.data.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.entity.CostView

/** A spend bucket: total minor units grouped by source/category. */
data class CategoryTotal(
    val bucket: String,
    val totalMinor: Long,
)

@Dao
interface CostDao {
    @Query("SELECT * FROM cost_view WHERE vehicleId = :vehicleId AND dateMillis >= :sinceMillis ORDER BY dateMillis ASC")
    fun observeSince(vehicleId: Long, sinceMillis: Long): Flow<List<CostView>>

    @Query("SELECT COALESCE(SUM(totalCostMinor), 0) FROM cost_view WHERE vehicleId = :vehicleId AND dateMillis >= :sinceMillis")
    fun observeTotalSince(vehicleId: Long, sinceMillis: Long): Flow<Long>

    /**
     * Spend grouped into buckets: fuel and charge each become one bucket; expenses split by
     * their [me.crsib.freecartracker.domain.model.ExpenseCategory] name.
     */
    @Query(
        """
        SELECT CASE sourceType WHEN 'EXPENSE' THEN category ELSE sourceType END AS bucket,
               SUM(totalCostMinor) AS totalMinor
        FROM cost_view
        WHERE vehicleId = :vehicleId AND dateMillis >= :sinceMillis
        GROUP BY bucket
        ORDER BY totalMinor DESC
        """,
    )
    fun observeByBucketSince(vehicleId: Long, sinceMillis: Long): Flow<List<CategoryTotal>>
}

package me.crsib.freecartracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import me.crsib.freecartracker.domain.model.ExpenseCategory
import me.crsib.freecartracker.domain.model.Recurrence

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = Vehicle::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("vehicleId", "dateMillis"),
        Index("vehicleId", "category"),
    ],
)
@Serializable
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val dateMillis: Long,
    val odometerKm: Double? = null,
    val category: ExpenseCategory,
    val totalCostMinor: Long,
    val currency: String,
    val vendor: String? = null,
    val description: String? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    /** Optional last billing date for a recurring cost (e.g. final loan payment). Null = ongoing. */
    val recurrenceEndMillis: Long? = null,
    /** Optional reminder: next due date (epoch millis). */
    val nextDueDateMillis: Long? = null,
    /** Optional reminder: next due at odometer (km). */
    val nextDueOdometerKm: Double? = null,
)

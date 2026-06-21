package me.crsib.freecartracker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.entity.Expense

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE vehicleId = :vehicleId ORDER BY dateMillis DESC, id DESC")
    fun observeForVehicle(vehicleId: Long): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE vehicleId = :vehicleId ORDER BY dateMillis ASC, id ASC")
    suspend fun getForVehicle(vehicleId: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): Expense?

    @Query("SELECT MAX(odometerKm) FROM expenses WHERE vehicleId = :vehicleId")
    suspend fun maxOdometer(vehicleId: Long): Double?

    @Query("SELECT * FROM expenses ORDER BY id")
    suspend fun getAll(): List<Expense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: Expense): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<Expense>)

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Update
    suspend fun update(entry: Expense)

    @Delete
    suspend fun delete(entry: Expense)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

package me.crsib.freecartracker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.entity.FuelEntry

@Dao
interface FuelEntryDao {
    @Query("SELECT * FROM fuel_entries WHERE vehicleId = :vehicleId ORDER BY dateMillis DESC, id DESC")
    fun observeForVehicle(vehicleId: Long): Flow<List<FuelEntry>>

    /** Ascending by odometer then date — the order the economy calculator expects. */
    @Query("SELECT * FROM fuel_entries WHERE vehicleId = :vehicleId ORDER BY odometerKm ASC, dateMillis ASC")
    fun observeForVehicleByOdometer(vehicleId: Long): Flow<List<FuelEntry>>

    @Query("SELECT * FROM fuel_entries WHERE vehicleId = :vehicleId ORDER BY odometerKm ASC, dateMillis ASC")
    suspend fun getForVehicleByOdometer(vehicleId: Long): List<FuelEntry>

    @Query("SELECT * FROM fuel_entries WHERE id = :id")
    suspend fun getById(id: Long): FuelEntry?

    @Query("SELECT * FROM fuel_entries ORDER BY id")
    suspend fun getAll(): List<FuelEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FuelEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<FuelEntry>)

    @Query("DELETE FROM fuel_entries")
    suspend fun deleteAll()

    @Update
    suspend fun update(entry: FuelEntry)

    @Delete
    suspend fun delete(entry: FuelEntry)

    @Query("DELETE FROM fuel_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}

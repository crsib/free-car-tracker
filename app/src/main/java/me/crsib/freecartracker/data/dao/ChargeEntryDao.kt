package me.crsib.freecartracker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.entity.ChargeEntry

@Dao
interface ChargeEntryDao {
    @Query("SELECT * FROM charge_entries WHERE vehicleId = :vehicleId ORDER BY dateMillis DESC, id DESC")
    fun observeForVehicle(vehicleId: Long): Flow<List<ChargeEntry>>

    @Query("SELECT * FROM charge_entries WHERE vehicleId = :vehicleId ORDER BY dateMillis ASC, id ASC")
    suspend fun getForVehicle(vehicleId: Long): List<ChargeEntry>

    @Query("SELECT * FROM charge_entries WHERE id = :id")
    suspend fun getById(id: Long): ChargeEntry?

    @Query("SELECT MAX(odometerKm) FROM charge_entries WHERE vehicleId = :vehicleId")
    suspend fun maxOdometer(vehicleId: Long): Double?

    @Query("SELECT * FROM charge_entries WHERE vehicleId = :vehicleId ORDER BY dateMillis DESC, id DESC LIMIT 1")
    suspend fun latest(vehicleId: Long): ChargeEntry?

    @Query("SELECT * FROM charge_entries ORDER BY id")
    suspend fun getAll(): List<ChargeEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ChargeEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ChargeEntry>)

    @Query("DELETE FROM charge_entries")
    suspend fun deleteAll()

    @Update
    suspend fun update(entry: ChargeEntry)

    @Delete
    suspend fun delete(entry: ChargeEntry)

    @Query("DELETE FROM charge_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}

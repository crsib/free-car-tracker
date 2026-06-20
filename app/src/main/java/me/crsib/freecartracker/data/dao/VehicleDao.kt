package me.crsib.freecartracker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.entity.Vehicle

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles ORDER BY isArchived, name COLLATE NOCASE")
    fun observeAll(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun observe(id: Long): Flow<Vehicle?>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun getById(id: Long): Vehicle?

    @Query("SELECT * FROM vehicles ORDER BY id")
    suspend fun getAll(): List<Vehicle>

    @Query("SELECT COUNT(*) FROM vehicles WHERE isArchived = 0")
    fun observeActiveCount(): Flow<Int>

    @Insert
    suspend fun insert(vehicle: Vehicle): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vehicles: List<Vehicle>)

    @Update
    suspend fun update(vehicle: Vehicle)

    @Delete
    suspend fun delete(vehicle: Vehicle)

    @Query("DELETE FROM vehicles")
    suspend fun deleteAll()
}

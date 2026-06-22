package me.crsib.freecartracker.telemetry.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint

@Dao
interface GpsPointDao {
    @Query("SELECT * FROM gps_points WHERE tripId = :tripId ORDER BY timestampMillis ASC, id ASC")
    fun observeForTrip(tripId: Long): Flow<List<GpsPoint>>

    @Query("SELECT * FROM gps_points WHERE tripId = :tripId ORDER BY timestampMillis ASC, id ASC")
    suspend fun getForTrip(tripId: Long): List<GpsPoint>

    @Query("SELECT COUNT(*) FROM gps_points WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: Long): Int

    @Insert
    suspend fun insertAll(points: List<GpsPoint>)
}

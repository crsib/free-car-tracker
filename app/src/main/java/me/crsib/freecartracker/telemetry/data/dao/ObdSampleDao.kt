package me.crsib.freecartracker.telemetry.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.telemetry.data.entity.ObdSample

@Dao
interface ObdSampleDao {
    @Query("SELECT * FROM obd_samples WHERE tripId = :tripId ORDER BY timestampMillis ASC, id ASC")
    fun observeForTrip(tripId: Long): Flow<List<ObdSample>>

    @Query("SELECT * FROM obd_samples WHERE tripId = :tripId ORDER BY timestampMillis ASC, id ASC")
    suspend fun getForTrip(tripId: Long): List<ObdSample>

    @Query("SELECT COUNT(*) FROM obd_samples WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: Long): Int

    /**
     * The most recent odometer reading (canonical km) across all of a vehicle's trips — from the
     * in-progress trip if one is recording, otherwise the last completed trip. Null when no trip
     * ever reported the odometer PID (0xA6).
     */
    @Query(
        "SELECT s.odometerKm FROM obd_samples s " +
            "JOIN trips t ON s.tripId = t.id " +
            "WHERE t.vehicleId = :vehicleId AND s.odometerKm IS NOT NULL " +
            "ORDER BY s.timestampMillis DESC LIMIT 1",
    )
    suspend fun latestOdometerForVehicle(vehicleId: Long): Double?

    @Insert
    suspend fun insertAll(samples: List<ObdSample>)
}

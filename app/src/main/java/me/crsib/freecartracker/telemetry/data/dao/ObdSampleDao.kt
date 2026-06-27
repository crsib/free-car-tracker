package me.crsib.freecartracker.telemetry.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.telemetry.data.entity.ObdSample

/** A trip's final odometer reading (km), from the [ObdSampleDao.finalOdometerByVehicle] query. */
data class TripOdometer(val tripId: Long, val odometerKm: Double)

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

    /**
     * Final odometer (km) per trip for a vehicle. Odometer (PID 0xA6) only increases, so MAX over a
     * trip's samples is its end reading. Trips that never reported it are absent from the result.
     */
    @Query(
        "SELECT s.tripId AS tripId, MAX(s.odometerKm) AS odometerKm FROM obd_samples s " +
            "JOIN trips t ON s.tripId = t.id " +
            "WHERE t.vehicleId = :vehicleId AND s.odometerKm IS NOT NULL " +
            "GROUP BY s.tripId",
    )
    suspend fun finalOdometerByVehicle(vehicleId: Long): List<TripOdometer>

    @Insert
    suspend fun insertAll(samples: List<ObdSample>)
}

package me.crsib.freecartracker.telemetry.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.telemetry.data.entity.ObdSample

/** A trip's final odometer reading (km), from the [ObdSampleDao.finalOdometerByVehicle] query. */
data class TripOdometer(val tripId: Long, val odometerKm: Double)

/** A trip's first/last fuel-tank level (%), from the [ObdSampleDao.fuelSpansByVehicle] query. */
data class TripFuelSpan(val tripId: Long, val startPct: Double?, val endPct: Double?)

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

    /**
     * Start and end fuel-tank level (%) per trip for a vehicle, each the mean of the first / last 5
     * non-null readings rather than a single raw sample — the fuel sender sloshes, so averaging a
     * short window damps the noise (the `LIMIT 5` matches `FUEL_ENDPOINT_WINDOW` on the UI side).
     * Used to estimate fuel burned (and average usage) from the tank drop when the car doesn't
     * report fuel rate. A side with no fuel reading comes back null.
     */
    @Query(
        "SELECT t.id AS tripId, " +
            "(SELECT AVG(x) FROM (SELECT a.fuelLevelPct AS x FROM obd_samples a " +
            "WHERE a.tripId = t.id AND a.fuelLevelPct IS NOT NULL " +
            "ORDER BY a.timestampMillis ASC, a.id ASC LIMIT 5)) AS startPct, " +
            "(SELECT AVG(x) FROM (SELECT b.fuelLevelPct AS x FROM obd_samples b " +
            "WHERE b.tripId = t.id AND b.fuelLevelPct IS NOT NULL " +
            "ORDER BY b.timestampMillis DESC, b.id DESC LIMIT 5)) AS endPct " +
            "FROM trips t WHERE t.vehicleId = :vehicleId",
    )
    suspend fun fuelSpansByVehicle(vehicleId: Long): List<TripFuelSpan>

    @Insert
    suspend fun insertAll(samples: List<ObdSample>)
}

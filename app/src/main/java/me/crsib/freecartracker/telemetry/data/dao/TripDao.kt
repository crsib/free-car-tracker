package me.crsib.freecartracker.telemetry.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.entity.Trip

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE vehicleId = :vehicleId ORDER BY startedAtMillis DESC, id DESC")
    fun observeForVehicle(vehicleId: Long): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: Long): Flow<Trip?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: Long): Trip?

    /** Any trip still flagged as recording — used to recover after process death. */
    @Query("SELECT * FROM trips WHERE status = :status ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun latestWithStatus(status: TripStatus = TripStatus.RECORDING): Trip?

    @Insert
    suspend fun insert(trip: Trip): Long

    @Update
    suspend fun update(trip: Trip)

    @Query(
        """
        UPDATE trips SET
            endedAtMillis = :endedAtMillis,
            status = :status,
            distanceKm = :distanceKm,
            durationSec = :durationSec,
            avgSpeedKmh = :avgSpeedKmh,
            maxSpeedKmh = :maxSpeedKmh,
            movingSec = :movingSec,
            fuelUsedL = :fuelUsedL,
            energyUsedKwh = :energyUsedKwh,
            avgConsumptionL100 = :avgConsumptionL100,
            obdSampleCount = :obdSampleCount,
            gpsPointCount = :gpsPointCount
        WHERE id = :id
        """,
    )
    suspend fun finalize(
        id: Long,
        endedAtMillis: Long?,
        status: TripStatus,
        distanceKm: Double?,
        durationSec: Long?,
        avgSpeedKmh: Double?,
        maxSpeedKmh: Double?,
        movingSec: Long?,
        fuelUsedL: Double?,
        energyUsedKwh: Double?,
        avgConsumptionL100: Double?,
        obdSampleCount: Int,
        gpsPointCount: Int,
    )

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)
}

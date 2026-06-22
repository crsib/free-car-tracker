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

    @Insert
    suspend fun insertAll(samples: List<ObdSample>)
}

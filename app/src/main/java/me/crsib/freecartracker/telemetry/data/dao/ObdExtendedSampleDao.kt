package me.crsib.freecartracker.telemetry.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample

@Dao
interface ObdExtendedSampleDao {
    @Query(
        "SELECT * FROM obd_extended_samples WHERE tripId = :tripId ORDER BY timestampMillis ASC, id ASC",
    )
    fun observeForTrip(tripId: Long): Flow<List<ObdExtendedSample>>

    @Query(
        "SELECT * FROM obd_extended_samples WHERE tripId = :tripId ORDER BY timestampMillis ASC, id ASC",
    )
    suspend fun getForTrip(tripId: Long): List<ObdExtendedSample>

    @Query(
        "SELECT * FROM obd_extended_samples WHERE tripId = :tripId AND metricKey = :metricKey ORDER BY timestampMillis ASC, id ASC",
    )
    suspend fun getForTripMetric(tripId: Long, metricKey: String): List<ObdExtendedSample>

    @Insert
    suspend fun insertAll(samples: List<ObdExtendedSample>)
}

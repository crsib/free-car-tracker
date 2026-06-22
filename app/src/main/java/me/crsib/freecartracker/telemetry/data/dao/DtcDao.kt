package me.crsib.freecartracker.telemetry.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.telemetry.data.entity.DtcRecord

@Dao
interface DtcDao {
    @Query("SELECT * FROM dtc_records WHERE tripId = :tripId ORDER BY timestampMillis ASC, id ASC")
    fun observeForTrip(tripId: Long): Flow<List<DtcRecord>>

    @Query("SELECT * FROM dtc_records WHERE tripId = :tripId ORDER BY timestampMillis ASC, id ASC")
    suspend fun getForTrip(tripId: Long): List<DtcRecord>

    @Insert
    suspend fun insertAll(records: List<DtcRecord>)
}

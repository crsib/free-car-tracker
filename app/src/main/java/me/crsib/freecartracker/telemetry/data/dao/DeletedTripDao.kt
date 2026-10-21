package me.crsib.freecartracker.telemetry.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.crsib.freecartracker.telemetry.data.entity.DeletedTrip

@Dao
interface DeletedTripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: DeletedTrip)

    /** Tombstones whose remote folder hasn't been purged yet — the push-side work list. */
    @Query("SELECT * FROM deleted_trips WHERE remotePurged = 0")
    suspend fun getUnpurged(): List<DeletedTrip>

    /** True if this trip has been deleted locally — used to skip re-import during pull. */
    @Query("SELECT EXISTS(SELECT 1 FROM deleted_trips WHERE tripSyncId = :tripSyncId)")
    suspend fun isTombstoned(tripSyncId: String): Boolean

    @Query("UPDATE deleted_trips SET remotePurged = 1 WHERE tripSyncId = :tripSyncId")
    suspend fun markPurged(tripSyncId: String)

    /** Re-queue every tombstone for a new sync target, which has never seen these deletions. */
    @Query("UPDATE deleted_trips SET remotePurged = 0 WHERE remotePurged = 1")
    suspend fun clearPurged(): Int
}

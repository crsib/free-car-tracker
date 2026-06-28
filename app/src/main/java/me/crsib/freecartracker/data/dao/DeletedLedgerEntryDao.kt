package me.crsib.freecartracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.crsib.freecartracker.data.entity.DeletedLedgerEntry

@Dao
interface DeletedLedgerEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: DeletedLedgerEntry)

    /** Tombstones for one car, used to build that car's ledger document at push time. */
    @Query("SELECT * FROM deleted_ledger_entries WHERE vehicleId = :vehicleId")
    suspend fun getForVehicle(vehicleId: Long): List<DeletedLedgerEntry>

    @Query("SELECT * FROM deleted_ledger_entries")
    suspend fun getAll(): List<DeletedLedgerEntry>

    /** True if this entry has been deleted locally — used to skip re-adding it during merge. */
    @Query("SELECT EXISTS(SELECT 1 FROM deleted_ledger_entries WHERE syncId = :syncId)")
    suspend fun isTombstoned(syncId: String): Boolean

    /** Drop a tombstone, e.g. when a newer live edit of the same entry wins the merge. */
    @Query("DELETE FROM deleted_ledger_entries WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)
}

package me.crsib.freecartracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tombstone for a deleted ledger entry (fuel/charge/expense). Recorded when the user deletes an
 * entry, so the deletion survives the merge sync: it is carried in the car's `ledger.json` and
 * honored by other devices (which delete their copy and stop re-adding it). Keyed by the entry's
 * cross-device [syncId] since the local row is gone by the time this exists. [vehicleId] groups
 * the tombstone into the right car's ledger document at push time.
 */
@Entity(tableName = "deleted_ledger_entries")
data class DeletedLedgerEntry(
    @PrimaryKey val syncId: String,
    val vehicleId: Long,
    val deletedAtMillis: Long,
)

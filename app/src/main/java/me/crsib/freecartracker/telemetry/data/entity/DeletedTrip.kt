package me.crsib.freecartracker.telemetry.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tombstone for a deleted trip. Recorded when the user deletes a trip that had been synced,
 * so that (a) WebDAV pull won't re-import it from the still-present remote folder, and
 * (b) the sync push can purge the remote folder and drop a tombstone marker that propagates
 * the deletion to other devices. Keyed by the trip's cross-device [tripSyncId] since the local
 * row (and its Long id) is gone by the time this exists.
 */
@Entity(tableName = "deleted_trips")
data class DeletedTrip(
    @PrimaryKey val tripSyncId: String,
    val vehicleSyncId: String,
    /** Trip start time — needed to rebuild the remote month-bucket folder path for the purge. */
    val startedAtMillis: Long,
    val deletedAtMillis: Long,
    /** True once the remote folder is purged and the remote tombstone marker is written. */
    val remotePurged: Boolean = false,
)

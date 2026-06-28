package me.crsib.freecartracker.data.sync

import androidx.room.withTransaction
import me.crsib.freecartracker.data.AppDatabase
import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.DeletedLedgerEntry
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.data.entity.LedgerRow

/**
 * Applies downloaded sync documents into the local database, matching by cross-device [syncId]
 * and remapping local Long ids so child rows follow (the MERGE id-remap pattern from
 * BackupRepository). Decompression is the caller's job — this takes plain bytes.
 */
class SyncImporter(private val db: AppDatabase) {

    /** Upsert a vehicle by syncId, preserving the local row id. Returns the local vehicle id. */
    suspend fun importVehicle(doc: VehicleDocument): Long {
        val existing = db.vehicleDao().findBySyncId(doc.vehicle.syncId)
        return if (existing != null) {
            db.vehicleDao().update(doc.vehicle.copy(id = existing.id))
            existing.id
        } else {
            db.vehicleDao().insert(doc.vehicle.copy(id = 0))
        }
    }

    /**
     * Merge one car's remote ledger into the local DB by entry [syncId] — a last-write-wins
     * element set. Entries the remote has and we don't are added; entries we have and it doesn't are
     * kept (this is what stops a concurrent local edit from being silently dropped); shared entries
     * resolve by `updatedAtMillis`; and [LedgerDocument.deletedEntries] tombstones delete the local
     * copy (and are recorded locally so they propagate). The vehicle must already exist locally
     * (import its vehicle.json first). Returns false if it doesn't.
     */
    suspend fun mergeLedger(doc: LedgerDocument): Boolean {
        val localId = db.vehicleDao().findBySyncId(doc.vehicleSyncId)?.id ?: return false
        // Ledgers from before schema v3 carry per-device random (or blank) entry ids, so the same
        // logical entry differs from device to device. Re-key them to the deterministic content id
        // the v8 migration produced locally, so they merge onto the local copy instead of doubling it.
        val v = doc.vehicleSyncId
        val fuelEntries = doc.fuelEntries.maybeRekey(doc.schemaVersion) { e ->
            e.copy(syncId = legacyLedgerSyncId("f", v, e.dateMillis, e.totalCostMinor))
        }
        val chargeEntries = doc.chargeEntries.maybeRekey(doc.schemaVersion) { e ->
            e.copy(syncId = legacyLedgerSyncId("c", v, e.dateMillis, e.totalCostMinor))
        }
        val expenses = doc.expenses.maybeRekey(doc.schemaVersion) { e ->
            e.copy(syncId = legacyLedgerSyncId("e", v, e.dateMillis, e.totalCostMinor))
        }
        val remoteTomb = doc.deletedEntries.associate { it.syncId to it.deletedAtMillis }
        db.withTransaction {
            val localTomb = db.deletedLedgerEntryDao().getForVehicle(localId)
                .associate { it.syncId to it.deletedAtMillis }
            mergeType(
                remote = fuelEntries,
                local = db.fuelEntryDao().getForVehicle(localId),
                localTomb = localTomb, remoteTomb = remoteTomb, localVehicleId = localId,
                rekey = { e: FuelEntry, existingId, vId -> e.copy(id = existingId ?: 0, vehicleId = vId) },
                upsert = { db.fuelEntryDao().upsert(it) },
                deleteBySyncId = { db.fuelEntryDao().deleteBySyncId(it) },
            )
            mergeType(
                remote = chargeEntries,
                local = db.chargeEntryDao().getForVehicle(localId),
                localTomb = localTomb, remoteTomb = remoteTomb, localVehicleId = localId,
                rekey = { e: ChargeEntry, existingId, vId -> e.copy(id = existingId ?: 0, vehicleId = vId) },
                upsert = { db.chargeEntryDao().upsert(it) },
                deleteBySyncId = { db.chargeEntryDao().deleteBySyncId(it) },
            )
            mergeType(
                remote = expenses,
                local = db.expenseDao().getForVehicle(localId),
                localTomb = localTomb, remoteTomb = remoteTomb, localVehicleId = localId,
                rekey = { e: Expense, existingId, vId -> e.copy(id = existingId ?: 0, vehicleId = vId) },
                upsert = { db.expenseDao().upsert(it) },
                deleteBySyncId = { db.expenseDao().deleteBySyncId(it) },
            )
        }
        return true
    }

    /**
     * LWW-element-set merge for one ledger entry type. For every [syncId] seen on either side or in
     * either tombstone set, the latest event wins: a deletion timestamp >= the newest live edit wins
     * and removes the row; otherwise the newer of the local/remote live rows is kept (the remote one
     * is re-keyed onto the local row id so child references and the unique syncId index hold).
     */
    private suspend fun <T : LedgerRow> mergeType(
        remote: List<T>,
        local: List<T>,
        localTomb: Map<String, Long>,
        remoteTomb: Map<String, Long>,
        localVehicleId: Long,
        rekey: (T, Long?, Long) -> T,
        upsert: suspend (T) -> Unit,
        deleteBySyncId: suspend (String) -> Unit,
    ) {
        val tombDao = db.deletedLedgerEntryDao()
        val localBySync = local.filter { it.syncId.isNotBlank() }.associateBy { it.syncId }
        val remoteBySync = remote.filter { it.syncId.isNotBlank() }.associateBy { it.syncId }
        val ids = localBySync.keys + remoteBySync.keys + localTomb.keys + remoteTomb.keys
        for (syncId in ids) {
            if (syncId.isBlank()) continue
            val localLive = localBySync[syncId]
            val remoteLive = remoteBySync[syncId]
            val delTs = maxOfNullable(localTomb[syncId], remoteTomb[syncId])
            val liveTs = maxOfNullable(localLive?.updatedAtMillis, remoteLive?.updatedAtMillis)

            if (delTs != null && (liveTs == null || delTs >= liveTs)) {
                if (localLive != null) deleteBySyncId(syncId)
                tombDao.upsert(DeletedLedgerEntry(syncId, localVehicleId, delTs))
            } else {
                val pickRemote = remoteLive != null &&
                    (localLive == null || remoteLive.updatedAtMillis >= localLive.updatedAtMillis)
                if (pickRemote) upsert(rekey(remoteLive!!, localLive?.id, localVehicleId))
                if (localTomb.containsKey(syncId)) tombDao.deleteBySyncId(syncId)
            }
        }
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? =
        when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

    /** Re-key every element when [schemaVersion] predates deterministic entry ids; else pass through. */
    private inline fun <T> List<T>.maybeRekey(schemaVersion: Int, transform: (T) -> T): List<T> =
        if (schemaVersion < LEDGER_SCHEMA_DETERMINISTIC_IDS) map(transform) else this

    /**
     * Import one finished trip. Skips if a trip with the same syncId already exists (trips are
     * immutable) or if the parent vehicle is missing locally. Inserts the trip plus its parsed
     * OBD/GPS samples and DTCs in a single transaction. Returns true if a new trip was inserted.
     */
    suspend fun importTrip(manifest: TripManifest, obdCsv: ByteArray?, gpx: ByteArray?, now: Long): Boolean {
        if (db.tripDao().findBySyncId(manifest.tripSyncId) != null) return false
        val localVehicleId = db.vehicleDao().findBySyncId(manifest.vehicleSyncId)?.id ?: return false

        db.withTransaction {
            val newTripId = db.tripDao().insert(
                manifest.trip.copy(id = 0, vehicleId = localVehicleId, syncedAtMillis = now),
            )
            gpx?.let { db.gpsPointDao().insertAll(GpxParser.parse(it, newTripId)) }
            obdCsv?.let {
                val (samples, extended) = ObdCsvParser.parse(it, newTripId)
                db.obdSampleDao().insertAll(samples)
                db.obdExtendedSampleDao().insertAll(extended)
            }
            db.dtcDao().insertAll(manifest.dtcs.map { it.copy(id = 0, tripId = newTripId) })
        }
        return true
    }

    companion object {
        /**
         * First [LedgerDocument.schemaVersion] whose entry `syncId`s are deterministic and
         * content-derived. Older snapshots carry per-device random ids and must be re-keyed on merge.
         */
        const val LEDGER_SCHEMA_DETERMINISTIC_IDS = 3

        /**
         * Deterministic cross-device id for a pre-v3 ledger entry, derived from its stable vehicle
         * [vehicleSyncId] plus integer-only content ([dateMillis], [totalCostMinor]) so two devices
         * compute the identical value for the same entry. Integer-only on purpose — float text
         * formatting differs between SQLite and Kotlin. [tag] (`f`/`c`/`e`) keeps the three entry
         * types from colliding in the shared id space (the tombstone table is keyed by syncId alone).
         *
         * MUST stay byte-for-byte identical to the SQL in `AppDatabase.MIGRATION_7_8`.
         */
        fun legacyLedgerSyncId(
            tag: String,
            vehicleSyncId: String,
            dateMillis: Long,
            totalCostMinor: Long,
        ): String = "l3:$tag:$vehicleSyncId:$dateMillis:$totalCostMinor"
    }
}

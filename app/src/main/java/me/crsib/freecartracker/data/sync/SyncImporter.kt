package me.crsib.freecartracker.data.sync

import androidx.room.withTransaction
import me.crsib.freecartracker.data.AppDatabase

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
     * Replace one car's ledger with the document's rows (per-car last-write-wins). The vehicle
     * must already exist locally (import its vehicle.json first). Returns false if it doesn't.
     */
    suspend fun importLedger(doc: LedgerDocument): Boolean {
        val localId = db.vehicleDao().findBySyncId(doc.vehicleSyncId)?.id ?: return false
        db.withTransaction {
            db.fuelEntryDao().deleteForVehicle(localId)
            db.chargeEntryDao().deleteForVehicle(localId)
            db.expenseDao().deleteForVehicle(localId)
            db.fuelEntryDao().insertAll(doc.fuelEntries.map { it.copy(id = 0, vehicleId = localId) })
            db.chargeEntryDao().insertAll(doc.chargeEntries.map { it.copy(id = 0, vehicleId = localId) })
            db.expenseDao().insertAll(doc.expenses.map { it.copy(id = 0, vehicleId = localId) })
        }
        return true
    }

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
}

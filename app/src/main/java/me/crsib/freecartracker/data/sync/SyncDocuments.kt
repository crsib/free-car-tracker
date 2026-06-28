package me.crsib.freecartracker.data.sync

import kotlinx.serialization.Serializable
import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.telemetry.data.entity.DtcRecord
import me.crsib.freecartracker.telemetry.data.entity.Trip

/** `vehicle.json` — the Vehicle record for one car, with an LWW clock. */
@Serializable
data class VehicleDocument(
    val schemaVersion: Int = 1,
    val exportedAt: Long,
    val vehicle: Vehicle,
)

/** A deleted ledger entry carried in `ledger.json` so the deletion propagates across devices. */
@Serializable
data class LedgerTombstone(
    val syncId: String,
    val deletedAtMillis: Long,
)

/**
 * `ledger.json` — one car's fuel/charge/expense snapshot. [exportedAt] is a coarse write clock;
 * actual conflict resolution is per-entry by `syncId` + `updatedAtMillis`, with [deletedEntries]
 * carrying tombstones so deletions aren't undone by a stale peer. `schemaVersion` history:
 * 1 = legacy whole-document snapshot (no per-entry ids); 2 = mergeable but entries carry *random*
 * per-device ids (the source of cross-device duplicates); 3 = entries carry *deterministic*
 * content-derived ids ([SyncImporter.legacyLedgerSyncId]), so the same entry has one id everywhere.
 * Readers re-key any document below version 3 on merge — see [SyncImporter.mergeLedger].
 */
@Serializable
data class LedgerDocument(
    val schemaVersion: Int = SyncImporter.LEDGER_SCHEMA_DETERMINISTIC_IDS,
    val exportedAt: Long,
    val vehicleSyncId: String,
    val fuelEntries: List<FuelEntry> = emptyList(),
    val chargeEntries: List<ChargeEntry> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val deletedEntries: List<LedgerTombstone> = emptyList(),
)

/** Names + counts of a trip's side files, so a reader knows what to fetch and can verify. */
@Serializable
data class TripFileManifest(
    val obdCsvGz: String? = null,
    val gpxGz: String? = null,
    val obdSampleCount: Int = 0,
    val gpsPointCount: Int = 0,
)

/**
 * `trip.json` — a finished trip's aggregates + DTCs + side-file manifest. Written last, so its
 * presence means the trip folder is fully uploaded.
 */
@Serializable
data class TripManifest(
    val schemaVersion: Int = 1,
    val tripSyncId: String,
    val vehicleSyncId: String,
    val trip: Trip,
    val dtcs: List<DtcRecord> = emptyList(),
    val files: TripFileManifest = TripFileManifest(),
)

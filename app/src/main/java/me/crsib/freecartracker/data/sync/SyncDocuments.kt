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

/** `ledger.json` — one car's fuel/charge/expense snapshot, with an LWW clock. */
@Serializable
data class LedgerDocument(
    val schemaVersion: Int = 1,
    val exportedAt: Long,
    val vehicleSyncId: String,
    val fuelEntries: List<FuelEntry> = emptyList(),
    val chargeEntries: List<ChargeEntry> = emptyList(),
    val expenses: List<Expense> = emptyList(),
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

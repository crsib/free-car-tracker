package me.crsib.freecartracker.data.webdav

import kotlinx.serialization.json.Json
import me.crsib.freecartracker.data.AppDatabase
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.sync.GpxExporter
import me.crsib.freecartracker.data.sync.LedgerDocument
import me.crsib.freecartracker.data.sync.LedgerTombstone
import me.crsib.freecartracker.data.sync.ObdCsvExporter
import me.crsib.freecartracker.data.sync.SyncImporter
import me.crsib.freecartracker.data.sync.TripFileManifest
import me.crsib.freecartracker.data.sync.TripManifest
import me.crsib.freecartracker.data.sync.VehicleDocument
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.TelemetryRepository

/** Counts of what a [WebDavSyncManager.syncNow] run pushed and pulled. */
data class SyncReport(
    val ledgersPushed: Int = 0,
    val tripsUploaded: Int = 0,
    val tripsDeleted: Int = 0,
    val vehiclesPulled: Int = 0,
    val ledgersPulled: Int = 0,
    val tripsImported: Int = 0,
    val tripsRemoved: Int = 0,
) {
    fun summary(): String =
        "↑ $ledgersPushed ledgers, $tripsUploaded trips, $tripsDeleted del · ↓ $vehiclesPulled cars, " +
            "$ledgersPulled ledgers, $tripsImported trips, $tripsRemoved del"
}

/**
 * Incremental, multi-device WebDAV sync over the per-car/per-trip folder layout
 * ([WebDavPaths]). Per-car ledgers use last-write-wins guarded by an `exportedAt` clock
 * ([SyncStateStore]); finished trips are immutable and sync by "upload what's not there yet".
 */
class WebDavSyncManager(
    private val store: WebDavSettingsStore,
    private val syncState: SyncStateStore,
    private val client: WebDavClient,
    private val db: AppDatabase,
    private val telemetryRepo: TelemetryRepository,
) {
    private val importer = SyncImporter(db)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun config(): WebDavConfig = store.load()
    fun saveConfig(config: WebDavConfig) = store.save(config)
    suspend fun testConnection(config: WebDavConfig): Result<Unit> = client.testConnection(config)

    /**
     * Upload one UDS DID-scan CSV. DIDs are vehicle-specific, so it lands under the car's folder
     * (`{vehicleSyncId}/did-scans/{name}.gz`); pass a null [vehicleSyncId] to use a top-level
     * `did-scans/` folder when no car is known. Stored gzipped, like trip files. Returns the remote
     * relative path. Independent of [syncNow] — the pull walk ignores this folder.
     */
    suspend fun uploadDidScan(vehicleSyncId: String?, name: String, csv: ByteArray): Result<String> =
        runCatching {
            val config = store.load()
            check(config.isConfigured) { "WebDAV not configured" }
            val dir = WebDavPaths.didScansDir(vehicleSyncId)
            client.ensureCollection(config, dir).getOrThrow()
            val remote = "$dir$name.gz"
            client.putGzip(config, remote, csv).getOrThrow()
            remote
        }

    /**
     * Upload a diagnostic CSV only if it isn't already on the server — the retry path for files whose
     * inline upload failed offline. Returns true if uploaded, false if it was already there.
     */
    suspend fun uploadDidScanIfMissing(vehicleSyncId: String?, name: String, csv: ByteArray): Result<Boolean> =
        runCatching {
            val config = store.load()
            check(config.isConfigured) { "WebDAV not configured" }
            val dir = WebDavPaths.didScansDir(vehicleSyncId)
            val remote = "$dir$name.gz"
            if (client.exists(config, remote).getOrDefault(false)) return@runCatching false
            client.ensureCollection(config, dir).getOrThrow()
            client.putGzip(config, remote, csv).getOrThrow()
            true
        }

    /**
     * Push local vehicles/ledgers/trips, then pull remote ones this device is missing. Cheap when
     * nothing changed (a few PROPFINDs). Safe to call from the worker on any trigger.
     */
    suspend fun syncNow(now: Long): Result<SyncReport> = runCatching {
        val config = store.load()
        check(config.isConfigured) { "WebDAV not configured" }
        var report = SyncReport()
        report = pushPhase(config, now, report)
        report = pullPhase(config, now, report)
        store.setLastBackup(now)
        report
    }

    // --- push ---

    private suspend fun pushPhase(config: WebDavConfig, now: Long, start: SyncReport): SyncReport {
        var report = start
        val vehicles = db.vehicleDao().getAll()
        val byId = vehicles.associateBy { it.id }

        for (v in vehicles) {
            client.ensureCollection(config, WebDavPaths.vehicleDir(v.syncId)).getOrThrow()
            val remoteLedgerBytes = client.getBytes(config, WebDavPaths.ledgerJson(v.syncId)).getOrThrow()
            val remoteDoc = remoteLedgerBytes?.let {
                runCatching { json.decodeFromString(LedgerDocument.serializer(), it.decodeToString()) }.getOrNull()
            }
            val remoteClock = remoteDoc?.exportedAt ?: -1L
            // Push unless the remote ledger is strictly newer than anything we've reconciled
            // (in which case pullPhase will merge it). For the sole ledger editor this is always true.
            if (remoteClock <= syncState.ledgerClock(v.syncId)) {
                if (remoteLedgerBytes != null) {
                    client.putBytes(config, WebDavPaths.ledgerBak(v.syncId), remoteLedgerBytes).getOrThrow()
                }
                // Fold any remote-only entries into the local ledger before overwriting the remote
                // copy, so a push can never discard entries another device added concurrently.
                if (remoteDoc != null) importer.mergeLedger(remoteDoc)
                pushVehicle(config, v, now)
                pushLedger(config, v, now)
                syncState.setLedgerClock(v.syncId, now)
                report = report.copy(ledgersPushed = report.ledgersPushed + 1)
            }
        }

        for (trip in db.tripDao().getUnsynced(TripStatus.COMPLETED)) {
            val vehicle = byId[trip.vehicleId] ?: continue
            if (uploadTrip(config, vehicle, trip, now)) {
                db.tripDao().markSynced(trip.id, now)
                report = report.copy(tripsUploaded = report.tripsUploaded + 1)
            }
        }

        report = pushTombstones(config, report)
        return report
    }

    /**
     * For every locally-deleted synced trip not yet purged: drop a tombstone marker (so other
     * devices delete their copy) then remove the remote trip folder, and mark it purged. The
     * marker is written before the folder delete so a crash mid-purge still propagates the intent.
     */
    private suspend fun pushTombstones(config: WebDavConfig, start: SyncReport): SyncReport {
        var report = start
        for (tomb in db.deletedTripDao().getUnpurged()) {
            client.ensureCollection(config, WebDavPaths.tombstonesDir(tomb.vehicleSyncId)).getOrThrow()
            client.putBytes(
                config,
                WebDavPaths.tombstoneFile(tomb.vehicleSyncId, tomb.tripSyncId),
                ByteArray(0),
            ).getOrThrow()
            client.delete(
                config,
                WebDavPaths.tripDir(tomb.vehicleSyncId, tomb.startedAtMillis, tomb.tripSyncId),
            ).getOrThrow()
            db.deletedTripDao().markPurged(tomb.tripSyncId)
            report = report.copy(tripsDeleted = report.tripsDeleted + 1)
        }
        return report
    }

    private suspend fun pushVehicle(config: WebDavConfig, v: Vehicle, now: Long) {
        val doc = VehicleDocument(exportedAt = now, vehicle = v)
        client.putBytes(
            config,
            WebDavPaths.vehicleJson(v.syncId),
            json.encodeToString(VehicleDocument.serializer(), doc).toByteArray(),
        ).getOrThrow()
    }

    private suspend fun pushLedger(config: WebDavConfig, v: Vehicle, now: Long) {
        val doc = LedgerDocument(
            exportedAt = now,
            vehicleSyncId = v.syncId,
            fuelEntries = db.fuelEntryDao().getForVehicle(v.id),
            chargeEntries = db.chargeEntryDao().getForVehicle(v.id),
            expenses = db.expenseDao().getForVehicle(v.id),
            deletedEntries = db.deletedLedgerEntryDao().getForVehicle(v.id)
                .map { LedgerTombstone(it.syncId, it.deletedAtMillis) },
        )
        client.putBytes(
            config,
            WebDavPaths.ledgerJson(v.syncId),
            json.encodeToString(LedgerDocument.serializer(), doc).toByteArray(),
        ).getOrThrow()
    }

    /** Upload one trip's folder; returns false if its data is gone. Idempotent (skips if present). */
    private suspend fun uploadTrip(config: WebDavConfig, vehicle: Vehicle, trip: me.crsib.freecartracker.telemetry.data.entity.Trip, now: Long): Boolean {
        val dir = WebDavPaths.tripDir(vehicle.syncId, trip.startedAtMillis, trip.syncId)
        if (client.exists(config, dir + WebDavPaths.TRIP_JSON).getOrThrow()) return true
        val data = telemetryRepo.getTripData(trip.id) ?: return false
        client.ensureCollection(config, dir).getOrThrow()
        client.putGzip(config, dir + WebDavPaths.OBD_CSV_GZ, ObdCsvExporter.export(data.obdSamples, data.extendedSamples)).getOrThrow()
        client.putGzip(config, dir + WebDavPaths.TRACK_GPX_GZ, GpxExporter.export(trip, data.gpsPoints)).getOrThrow()
        val manifest = TripManifest(
            tripSyncId = trip.syncId,
            vehicleSyncId = vehicle.syncId,
            trip = trip,
            dtcs = data.dtcs,
            files = TripFileManifest(
                obdCsvGz = WebDavPaths.OBD_CSV_GZ,
                gpxGz = WebDavPaths.TRACK_GPX_GZ,
                obdSampleCount = data.obdSamples.size,
                gpsPointCount = data.gpsPoints.size,
            ),
        )
        // Manifest last: its presence means the folder is complete.
        client.putBytes(config, dir + WebDavPaths.TRIP_JSON, json.encodeToString(TripManifest.serializer(), manifest).toByteArray()).getOrThrow()
        return true
    }

    // --- pull ---

    private suspend fun pullPhase(config: WebDavConfig, now: Long, start: SyncReport): SyncReport {
        var report = start
        val vehicleDirs = client.list(config, "").getOrThrow().filter { it.isCollection }
        for (dir in vehicleDirs) {
            val vSync = dir.name
            val vehicleDoc = getJson(config, WebDavPaths.vehicleJson(vSync), VehicleDocument.serializer()) ?: continue
            val local = db.vehicleDao().findBySyncId(vSync)
            val remoteLedger = getJson(config, WebDavPaths.ledgerJson(vSync), LedgerDocument.serializer())

            if (local == null) {
                importer.importVehicle(vehicleDoc)
                remoteLedger?.let {
                    importer.mergeLedger(it)
                    syncState.setLedgerClock(vSync, it.exportedAt)
                }
                report = report.copy(vehiclesPulled = report.vehiclesPulled + 1)
            } else if (remoteLedger != null && remoteLedger.exportedAt > syncState.ledgerClock(vSync)) {
                importer.importVehicle(vehicleDoc)
                importer.mergeLedger(remoteLedger)
                syncState.setLedgerClock(vSync, remoteLedger.exportedAt)
                report = report.copy(ledgersPulled = report.ledgersPulled + 1)
            }

            report = applyRemoteTombstones(config, vSync, now, report)
            report = importNewTrips(config, vSync, now, report)
        }
        return report
    }

    /**
     * Honor tombstone markers another device left under `{vSync}/tombstones/`: delete the matching
     * local trip if we still have it, and record a local tombstone (already purged) so [importNewTrips]
     * skips it even if the remote folder hasn't been removed yet.
     */
    private suspend fun applyRemoteTombstones(config: WebDavConfig, vSync: String, now: Long, start: SyncReport): SyncReport {
        var report = start
        val markers = client.list(config, WebDavPaths.tombstonesDir(vSync)).getOrThrow().filter { !it.isCollection }
        for (marker in markers) {
            val tripSyncId = marker.name
            if (db.deletedTripDao().isTombstoned(tripSyncId)) continue
            val local = db.tripDao().findBySyncId(tripSyncId)
            if (local != null) {
                db.tripDao().deleteById(local.id)
                report = report.copy(tripsRemoved = report.tripsRemoved + 1)
            }
            db.deletedTripDao().upsert(
                me.crsib.freecartracker.telemetry.data.entity.DeletedTrip(
                    tripSyncId = tripSyncId,
                    vehicleSyncId = vSync,
                    startedAtMillis = local?.startedAtMillis ?: 0L,
                    deletedAtMillis = now,
                    remotePurged = true,
                ),
            )
        }
        return report
    }

    /** Walk trips/YYYY/MM/{tripSyncId}/ and import any trip not already present locally. */
    private suspend fun importNewTrips(config: WebDavConfig, vSync: String, now: Long, start: SyncReport): SyncReport {
        var report = start
        val years = client.list(config, WebDavPaths.tripsRoot(vSync)).getOrThrow().filter { it.isCollection }
        for (y in years) {
            val months = client.list(config, "${WebDavPaths.tripsRoot(vSync)}${y.name}/").getOrThrow().filter { it.isCollection }
            for (m in months) {
                val tripDirs = client.list(config, "${WebDavPaths.tripsRoot(vSync)}${y.name}/${m.name}/").getOrThrow().filter { it.isCollection }
                for (t in tripDirs) {
                    if (db.tripDao().findBySyncId(t.name) != null) continue
                    if (db.deletedTripDao().isTombstoned(t.name)) continue
                    val base = "${WebDavPaths.tripsRoot(vSync)}${y.name}/${m.name}/${t.name}/"
                    val manifest = getJson(config, base + WebDavPaths.TRIP_JSON, TripManifest.serializer()) ?: continue
                    val obd = manifest.files.obdCsvGz?.let { client.getGunzip(config, base + it).getOrThrow() }
                    val gpx = manifest.files.gpxGz?.let { client.getGunzip(config, base + it).getOrThrow() }
                    if (importer.importTrip(manifest, obd, gpx, now)) {
                        report = report.copy(tripsImported = report.tripsImported + 1)
                    }
                }
            }
        }
        return report
    }

    private suspend fun <T> getJson(config: WebDavConfig, relPath: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val bytes = client.getBytes(config, relPath).getOrThrow() ?: return null
        return runCatching { json.decodeFromString(serializer, bytes.decodeToString()) }.getOrNull()
    }
}

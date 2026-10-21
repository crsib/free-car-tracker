package me.crsib.freecartracker.data.webdav

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
import me.crsib.freecartracker.telemetry.DiagLog
import me.crsib.freecartracker.telemetry.data.TelemetryRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Counts of what a [WebDavSyncManager.syncNow] run pushed and pulled. */
data class SyncReport(
    val ledgersPushed: Int = 0,
    val tripsUploaded: Int = 0,
    val tripsDeleted: Int = 0,
    val vehiclesPulled: Int = 0,
    val ledgersPulled: Int = 0,
    val tripsImported: Int = 0,
    val tripsRemoved: Int = 0,
    /** Items that errored individually; the rest of the run continued past them. */
    val failed: Int = 0,
    /** Items left untouched because the run hit its time budget. */
    val deferred: Int = 0,
) {
    /** True when the run got through everything — nothing errored and nothing was left over. */
    val isComplete: Boolean get() = failed == 0 && deferred == 0

    fun summary(): String = buildString {
        append(
            "↑ $ledgersPushed ledgers, $tripsUploaded trips, $tripsDeleted del · ↓ $vehiclesPulled cars, " +
                "$ledgersPulled ledgers, $tripsImported trips, $tripsRemoved del",
        )
        if (failed > 0) append(" · $failed failed")
        if (deferred > 0) append(" · $deferred deferred")
    }
}

/**
 * What a sync run is doing right now. Published on [WebDavSyncManager.status], which is app-scoped,
 * so a screen shows the live step whether the run was started by "Sync now" or by the background
 * worker — and the last outcome stays readable after it finishes.
 */
sealed interface SyncStatus {
    /** Nothing has run yet in this process. */
    data object Idle : SyncStatus

    /**
     * A run is in flight. [done] is 1-based and counts the item being worked on *now* — a sweep of
     * 61 trips reads "1/61" while the first is uploading, not "0/61", which reads as a stall.
     * [total] is 0 for a step that isn't a countable sweep; [detail] is optional extra context
     * (e.g. how big the current upload is) so a long pause is explicable.
     */
    data class Running(
        val step: String,
        val done: Int = 0,
        val total: Int = 0,
        val detail: String = "",
    ) : SyncStatus {
        val label: String
            get() = buildString {
                append(step)
                when {
                    total > 0 -> append(" ($done/$total)")
                    done > 0 -> append(" ($done)")
                }
                if (detail.isNotEmpty()) append(" · $detail")
            }
    }

    data class Done(val report: SyncReport, val finishedAtMillis: Long) : SyncStatus

    data class Failed(val reason: String, val finishedAtMillis: Long) : SyncStatus
}

/**
 * Incremental, multi-device sync to WebDAV or S3 ([RemoteStorage]) over the per-car/per-trip folder layout
 * ([WebDavPaths]). Per-car ledgers use last-write-wins guarded by an `exportedAt` clock
 * ([SyncStateStore]); finished trips are immutable and sync by "upload what's not there yet".
 *
 * A run is **best-effort, not all-or-nothing**: one car or one trip that errors is logged, counted
 * in [SyncReport.failed] and stepped over, so a single unuploadable trip can no longer stall every
 * other car's ledger and the whole pull phase behind it. Only a failure that makes the run
 * meaningless at all (no config, unreachable root) aborts and surfaces as a failed [Result].
 */
class WebDavSyncManager(
    private val store: WebDavSettingsStore,
    private val syncState: SyncStateStore,
    private val client: WebDavClient,
    private val s3: S3Client = S3Client(),
    private val db: AppDatabase,
    private val telemetryRepo: TelemetryRepository,
) {
    private val importer = SyncImporter(db)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /** Live step of the current run, or the outcome of the last one. See [SyncStatus]. */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * One run at a time. The worker and the "Sync now" button share this manager, and two runs
     * interleaving would race on `markSynced`/the ledger clock as well as make [status] flap
     * between them.
     */
    private val runLock = Mutex()

    /**
     * Collection paths already MKCOL'd during the current run, so a 61-trip backlog stops
     * re-creating the same `{car}/trips/YYYY/MM/` chain once per trip. Reset per run — see
     * [WebDavClient.ensureCollection].
     */
    private val ensuredCollections: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun config(): WebDavConfig = store.load()
    fun saveConfig(config: WebDavConfig) = store.save(config)
    suspend fun testConnection(config: WebDavConfig): Result<Unit> = storage(config).testConnection()

    /** The configured sync target, WebDAV or S3. Every operation below goes through this. */
    private fun storage(config: WebDavConfig): RemoteStorage = RemoteStorage.forConfig(config, client, s3)

    /** The live target, or a failure naming the reason nothing can be synced. */
    private fun configuredStorage(): RemoteStorage {
        val config = store.load()
        check(config.isConfigured) { "Sync storage not configured" }
        return storage(config)
    }

    /**
     * Upload one UDS DID-scan CSV. DIDs are vehicle-specific, so it lands under the car's folder
     * (`{vehicleSyncId}/did-scans/{name}.gz`); pass a null [vehicleSyncId] to use a top-level
     * `did-scans/` folder when no car is known. Stored gzipped, like trip files. Returns the remote
     * relative path. Independent of [syncNow] — the pull walk ignores this folder.
     */
    suspend fun uploadDidScan(vehicleSyncId: String?, name: String, csv: ByteArray): Result<String> =
        runCatching {
            val remote = configuredStorage()
            val dir = WebDavPaths.didScansDir(vehicleSyncId)
            remote.ensureCollection(dir).getOrThrow()
            val path = "$dir$name.gz"
            remote.putGzip(path, csv).getOrThrow()
            path
        }

    /**
     * Upload a diagnostic CSV only if it isn't already on the server — the retry path for files whose
     * inline upload failed offline. Returns true if uploaded, false if it was already there.
     */
    suspend fun uploadDidScanIfMissing(vehicleSyncId: String?, name: String, csv: ByteArray): Result<Boolean> =
        runCatching {
            val remote = configuredStorage()
            val dir = WebDavPaths.didScansDir(vehicleSyncId)
            val path = "$dir$name.gz"
            if (remote.exists(path).getOrDefault(false)) return@runCatching false
            remote.ensureCollection(dir).getOrThrow()
            remote.putGzip(path, csv).getOrThrow()
            true
        }

    /**
     * Push local vehicles/ledgers/trips, then pull remote ones this device is missing. Cheap when
     * nothing changed (a few PROPFINDs). Safe to call from the worker on any trigger.
     *
     * [budgetMillis] bounds how long the run keeps starting new transfers — the worker has a hard
     * execution limit, and a backlog too big for one run must end cleanly with its progress
     * committed rather than be killed mid-upload every time and never converge. Pass 0 for no bound
     * (manual sync, tests). Whatever it did not reach is [SyncReport.deferred], not a failure.
     */
    suspend fun syncNow(now: Long, budgetMillis: Long = 0L): Result<SyncReport> = runLock.withLock {
        step("Starting")
        ensuredCollections.clear()
        val result = runCatching {
            val remote = configuredStorage()
            adoptTarget(store.load())
            val budget = Budget(budgetMillis)
            var report = SyncReport()
            report = pushPhase(remote, now, report, budget)
            report = pullPhase(remote, now, report, budget)
            store.setLastBackup(now)
            report
        }
        _status.value = result.fold(
            onSuccess = { SyncStatus.Done(it, now) },
            onFailure = { SyncStatus.Failed("${it::class.simpleName}: ${it.message}", now) },
        )
        result
    }

    /**
     * Re-offer everything when the sync target is not the one the local upload marks refer to.
     *
     * A trip's `syncedAtMillis` and a tombstone's `remotePurged` mean "done on the server we were
     * syncing with". After switching WebDAV → S3 (or to another folder) they are stale, and
     * without this the new target only ever received trips recorded after the switch. Clearing
     * them is safe: an upload first checks whether the trip is already there, so trips the new
     * target does have cost one existence check each, not a re-upload. That includes trips this
     * device pulled from others, so any one device can populate a new target on its own.
     *
     * Installs from before targets were recorded have no id yet. They get one full re-check, since
     * there is no way to tell whether their marks belong to the current target — the price is one
     * existence check per trip, once.
     */
    private suspend fun adoptTarget(config: WebDavConfig) {
        val target = config.targetId
        if (syncState.syncedTarget() == target) return
        val trips = db.tripDao().clearSynced()
        val tombstones = db.deletedTripDao().clearPurged()
        DiagLog.i(TAG, "sync target is now $target: re-offering $trips trips and $tombstones deletions")
        syncState.setSyncedTarget(target)
    }

    /**
     * Publish the step the run is on, for the live UI readout. Also logged — but only when the step
     * *changes*, not on every counter tick, so a 200-trip backlog leaves a readable trace instead of
     * 200 lines. Matters on a head unit, where the log is the only way to watch a background run.
     */
    private fun step(step: String, done: Int = 0, total: Int = 0) {
        val previous = _status.value
        if (previous !is SyncStatus.Running || previous.step != step) DiagLog.i(TAG, step)
        _status.value = SyncStatus.Running(step, done, total)
    }

    /** Add context to the step already in progress, without disturbing its counter. */
    private fun detail(text: String) {
        val current = _status.value
        if (current is SyncStatus.Running) _status.value = current.copy(detail = text)
    }

    // --- push ---

    private suspend fun pushPhase(remote: RemoteStorage, now: Long, start: SyncReport, budget: Budget): SyncReport {
        var report = start
        val vehicles = db.vehicleDao().getAll()
        val byId = vehicles.associateBy { it.id }

        for (v in vehicles) {
            // Named rather than counted: people have one or two cars, so the name says more.
            step("Uploading ledger for ${v.name}")
            report = runCatching { pushVehicleLedger(remote, v, now) }.fold(
                onSuccess = { pushed ->
                    if (pushed) report.copy(ledgersPushed = report.ledgersPushed + 1) else report
                },
                onFailure = { e -> report.failing("push ledger ${v.syncId}", e) },
            )
        }

        report = uploadTrips(remote, byId, now, report, budget)
        return pushTombstones(remote, report)
    }

    /**
     * Upload the outstanding trips [UPLOAD_CONCURRENCY] at a time.
     *
     * Uploads are run concurrently because the work is not purely bandwidth-bound: each trip costs
     * a PROPFIND, an MKCOL or two and three PUTs, and a single connection is additionally capped by
     * TCP window ÷ round-trip-time, which on a distant server sits far below the link's capacity.
     * Overlapping transfers fills those gaps. If the uplink really is saturated this changes
     * nothing (the same bytes share the same pipe) — it cannot make things slower, and the
     * per-trip timings in the log say which regime we are in.
     *
     * Kept deliberately low: each in-flight trip holds its samples, the rendered CSV and the
     * gzipped copy in memory at once, and some servers lock a collection during concurrent writes.
     */
    private suspend fun uploadTrips(
        remote: RemoteStorage,
        byId: Map<Long, Vehicle>,
        now: Long,
        start: SyncReport,
        budget: Budget,
    ): SyncReport = coroutineScope {
        val pending = db.tripDao().getUnsynced(TripStatus.COMPLETED)
        val gate = Semaphore(UPLOAD_CONCURRENCY)
        val finished = AtomicInteger()

        val outcomes = pending.map { trip ->
            async {
                val vehicle = byId[trip.vehicleId] ?: return@async Outcome.SKIPPED
                if (budget.exhausted()) return@async Outcome.DEFERRED
                gate.withPermit {
                    // Re-check inside the permit: the budget may have run out while queued.
                    if (budget.exhausted()) return@withPermit Outcome.DEFERRED
                    val startedAt = System.nanoTime()
                    runCatching { uploadTrip(remote, vehicle, trip, now) }.fold(
                        onSuccess = { uploaded ->
                            if (uploaded) {
                                db.tripDao().markSynced(trip.id, now)
                                DiagLog.i(TAG, "trip ${trip.syncId} uploaded in ${elapsedLabel(startedAt)}")
                                Outcome.UPLOADED
                            } else {
                                Outcome.SKIPPED
                            }
                        },
                        onFailure = { e ->
                            DiagLog.w(
                                TAG,
                                "upload trip ${trip.syncId} failed after ${elapsedLabel(startedAt)}: " +
                                    "${e::class.simpleName}: ${e.message}",
                            )
                            Outcome.FAILED
                        },
                    ).also { step("Uploading trips", done = finished.incrementAndGet(), total = pending.size) }
                }
            }
        }.awaitAll()

        // Fold once, off the concurrent path — SyncReport is immutable and counting it inside the
        // coroutines would race.
        start.copy(
            tripsUploaded = start.tripsUploaded + outcomes.count { it == Outcome.UPLOADED },
            failed = start.failed + outcomes.count { it == Outcome.FAILED },
            deferred = start.deferred + outcomes.count { it == Outcome.DEFERRED },
        )
    }

    /**
     * Reconcile and push one car's vehicle.json + ledger.json. Returns false without pushing when
     * the remote ledger is strictly newer than anything we have reconciled — [pullPhase] merges it
     * instead. For the sole ledger editor that never happens and we always push.
     */
    private suspend fun pushVehicleLedger(remote: RemoteStorage, v: Vehicle, now: Long): Boolean {
        remote.ensureCollection(WebDavPaths.vehicleDir(v.syncId), ensuredCollections).getOrThrow()
        val remoteLedgerBytes = remote.getBytes(WebDavPaths.ledgerJson(v.syncId)).getOrThrow()
        val remoteDoc = remoteLedgerBytes?.let {
            runCatching { json.decodeFromString(LedgerDocument.serializer(), it.decodeToString()) }.getOrNull()
        }
        val remoteClock = remoteDoc?.exportedAt ?: -1L
        if (remoteClock > syncState.ledgerClock(v.syncId)) return false

        if (remoteLedgerBytes != null) {
            remote.putBytes(WebDavPaths.ledgerBak(v.syncId), remoteLedgerBytes).getOrThrow()
        }
        // Fold any remote-only entries into the local ledger before overwriting the remote
        // copy, so a push can never discard entries another device added concurrently.
        if (remoteDoc != null) importer.mergeLedger(remoteDoc)
        pushVehicle(remote, v, now)
        pushLedger(remote, v, now)
        syncState.setLedgerClock(v.syncId, now)
        return true
    }

    /**
     * For every locally-deleted synced trip not yet purged: drop a tombstone marker (so other
     * devices delete their copy) then remove the remote trip folder, and mark it purged. The
     * marker is written before the folder delete so a crash mid-purge still propagates the intent.
     */
    private suspend fun pushTombstones(remote: RemoteStorage, start: SyncReport): SyncReport {
        var report = start
        val pending = db.deletedTripDao().getUnpurged()
        for ((i, tomb) in pending.withIndex()) {
            step("Removing deleted trips", done = i + 1, total = pending.size)
            report = runCatching {
                remote.ensureCollection(WebDavPaths.tombstonesDir(tomb.vehicleSyncId), ensuredCollections).getOrThrow()
                remote.putBytes(
                    WebDavPaths.tombstoneFile(tomb.vehicleSyncId, tomb.tripSyncId),
                    ByteArray(0),
                ).getOrThrow()
                remote.delete(
                    WebDavPaths.tripDir(tomb.vehicleSyncId, tomb.startedAtMillis, tomb.tripSyncId),
                ).getOrThrow()
                db.deletedTripDao().markPurged(tomb.tripSyncId)
            }.fold(
                onSuccess = { report.copy(tripsDeleted = report.tripsDeleted + 1) },
                onFailure = { e -> report.failing("purge trip ${tomb.tripSyncId}", e) },
            )
        }
        return report
    }

    private suspend fun pushVehicle(remote: RemoteStorage, v: Vehicle, now: Long) {
        val doc = VehicleDocument(exportedAt = now, vehicle = v)
        remote.putBytes(
            WebDavPaths.vehicleJson(v.syncId),
            json.encodeToString(VehicleDocument.serializer(), doc).toByteArray(),
        ).getOrThrow()
    }

    private suspend fun pushLedger(remote: RemoteStorage, v: Vehicle, now: Long) {
        val doc = LedgerDocument(
            exportedAt = now,
            vehicleSyncId = v.syncId,
            fuelEntries = db.fuelEntryDao().getForVehicle(v.id),
            chargeEntries = db.chargeEntryDao().getForVehicle(v.id),
            expenses = db.expenseDao().getForVehicle(v.id),
            deletedEntries = db.deletedLedgerEntryDao().getForVehicle(v.id)
                .map { LedgerTombstone(it.syncId, it.deletedAtMillis) },
        )
        remote.putBytes(
            WebDavPaths.ledgerJson(v.syncId),
            json.encodeToString(LedgerDocument.serializer(), doc).toByteArray(),
        ).getOrThrow()
    }

    /** Upload one trip's folder; returns false if its data is gone. Idempotent (skips if present). */
    private suspend fun uploadTrip(remote: RemoteStorage, vehicle: Vehicle, trip: me.crsib.freecartracker.telemetry.data.entity.Trip, now: Long): Boolean {
        val dir = WebDavPaths.tripDir(vehicle.syncId, trip.startedAtMillis, trip.syncId)
        if (remote.exists(dir + WebDavPaths.TRIP_JSON).getOrThrow()) return true
        val data = telemetryRepo.getTripData(trip.id) ?: return false
        val obdGz = gzip(ObdCsvExporter.export(data.obdSamples, data.extendedSamples))
        val gpxGz = gzip(GpxExporter.export(trip, data.gpsPoints))
        // A single 10 Hz trip can be tens of MB; showing the size is the difference between the UI
        // looking hung and it obviously being busy.
        detail(sizeLabel(obdGz.size + gpxGz.size))
        remote.ensureCollection(dir, ensuredCollections).getOrThrow()
        remote.putGzipped(dir + WebDavPaths.OBD_CSV_GZ, obdGz).getOrThrow()
        remote.putGzipped(dir + WebDavPaths.TRACK_GPX_GZ, gpxGz).getOrThrow()
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
        remote.putBytes(dir + WebDavPaths.TRIP_JSON, json.encodeToString(TripManifest.serializer(), manifest).toByteArray()).getOrThrow()
        return true
    }

    // --- pull ---

    private suspend fun pullPhase(remote: RemoteStorage, now: Long, start: SyncReport, budget: Budget): SyncReport {
        var report = start
        step("Checking the server")
        // The root listing is the one thing a pull genuinely cannot proceed without, so let it throw.
        val vehicleDirs = remote.list("").getOrThrow().filter { it.isCollection }
        vehicleDirs.forEachIndexed { i, dir ->
            val vSync = dir.name
            step("Checking for new data", done = i + 1, total = vehicleDirs.size)
            report = runCatching { pullVehicle(remote, vSync, now, report, budget) }
                .getOrElse { e -> report.failing("pull car $vSync", e) }
        }
        return report
    }

    private suspend fun pullVehicle(
        remote: RemoteStorage,
        vSync: String,
        now: Long,
        start: SyncReport,
        budget: Budget,
    ): SyncReport {
        var report = start
        // Not a car folder (e.g. the top-level did-scans/) — nothing to pull.
        val vehicleDoc = getJson(remote, WebDavPaths.vehicleJson(vSync), VehicleDocument.serializer()) ?: return report
        val local = db.vehicleDao().findBySyncId(vSync)
        val remoteLedger = getJson(remote, WebDavPaths.ledgerJson(vSync), LedgerDocument.serializer())

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

        report = applyRemoteTombstones(remote, vSync, now, report)
        return importNewTrips(remote, vSync, now, report, budget)
    }

    /**
     * Honor tombstone markers another device left under `{vSync}/tombstones/`: delete the matching
     * local trip if we still have it, and record a local tombstone (already purged) so [importNewTrips]
     * skips it even if the remote folder hasn't been removed yet.
     */
    private suspend fun applyRemoteTombstones(remote: RemoteStorage, vSync: String, now: Long, start: SyncReport): SyncReport {
        var report = start
        val markers = remote.list(WebDavPaths.tombstonesDir(vSync)).getOrThrow().filter { !it.isCollection }
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
    private suspend fun importNewTrips(
        remote: RemoteStorage,
        vSync: String,
        now: Long,
        start: SyncReport,
        budget: Budget,
    ): SyncReport {
        var report = start
        var attempted = 0
        val years = remote.list(WebDavPaths.tripsRoot(vSync)).getOrThrow().filter { it.isCollection }
        for (y in years) {
            val months = remote.list("${WebDavPaths.tripsRoot(vSync)}${y.name}/").getOrThrow().filter { it.isCollection }
            for (m in months) {
                val tripDirs = remote.list("${WebDavPaths.tripsRoot(vSync)}${y.name}/${m.name}/").getOrThrow().filter { it.isCollection }
                for (t in tripDirs) {
                    if (db.tripDao().findBySyncId(t.name) != null) continue
                    if (db.deletedTripDao().isTombstoned(t.name)) continue
                    if (budget.exhausted()) {
                        report = report.copy(deferred = report.deferred + 1)
                        continue
                    }
                    step("Downloading trips", done = ++attempted)
                    val base = "${WebDavPaths.tripsRoot(vSync)}${y.name}/${m.name}/${t.name}/"
                    report = runCatching { importOneTrip(remote, base, now) }.fold(
                        onSuccess = { imported ->
                            if (imported) report.copy(tripsImported = report.tripsImported + 1) else report
                        },
                        onFailure = { e -> report.failing("import trip ${t.name}", e) },
                    )
                }
            }
        }
        return report
    }

    private suspend fun importOneTrip(remote: RemoteStorage, base: String, now: Long): Boolean {
        val manifest = getJson(remote, base + WebDavPaths.TRIP_JSON, TripManifest.serializer()) ?: return false
        val obd = manifest.files.obdCsvGz?.let { remote.getGunzip(base + it).getOrThrow() }
        val gpx = manifest.files.gpxGz?.let { remote.getGunzip(base + it).getOrThrow() }
        return importer.importTrip(manifest, obd, gpx, now)
    }

    private suspend fun <T> getJson(remote: RemoteStorage, relPath: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val bytes = remote.getBytes(relPath).getOrThrow() ?: return null
        return runCatching { json.decodeFromString(serializer, bytes.decodeToString()) }.getOrNull()
    }

    /** Log one skipped item and count it, so the run can carry on with the rest. */
    private fun SyncReport.failing(what: String, e: Throwable): SyncReport {
        DiagLog.w(TAG, "$what failed: ${e::class.simpleName}: ${e.message}")
        return copy(failed = failed + 1)
    }

    /**
     * Elapsed-time budget for one run. Measured monotonically from construction rather than against
     * the caller's `now`, so a synthetic clock (tests pass `now = 1000`) never looks already expired.
     */
    private class Budget(private val budgetMillis: Long) {
        private val startedAtNanos = System.nanoTime()

        fun exhausted(): Boolean =
            budgetMillis > 0L && (System.nanoTime() - startedAtNanos) / 1_000_000L >= budgetMillis
    }

    /** What one trip's upload attempt came to, tallied after the concurrent phase completes. */
    private enum class Outcome { UPLOADED, SKIPPED, FAILED, DEFERRED }

    private companion object {
        const val TAG = "WebDavSync"

        /**
         * Concurrent trip uploads. Low on purpose — see [uploadTrips] for the memory and
         * server-locking constraints that stop this being larger.
         */
        const val UPLOAD_CONCURRENCY = 3

        fun elapsedLabel(startedAtNanos: Long): String =
            "%.1fs".format((System.nanoTime() - startedAtNanos) / 1_000_000_000.0)

        fun sizeLabel(bytes: Int): String = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        }
    }
}

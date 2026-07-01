package me.crsib.freecartracker.telemetry.data

import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.telemetry.TripAggregator
import me.crsib.freecartracker.data.dao.VehicleDao
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.dao.DeletedTripDao
import me.crsib.freecartracker.telemetry.data.dao.DtcDao
import me.crsib.freecartracker.telemetry.data.dao.GpsPointDao
import me.crsib.freecartracker.telemetry.data.dao.ObdExtendedSampleDao
import me.crsib.freecartracker.telemetry.data.dao.ObdSampleDao
import me.crsib.freecartracker.telemetry.data.dao.TripDao
import me.crsib.freecartracker.telemetry.data.dao.TripFuelSpan
import me.crsib.freecartracker.telemetry.data.entity.DeletedTrip
import me.crsib.freecartracker.telemetry.data.entity.DtcRecord
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import me.crsib.freecartracker.telemetry.data.entity.Trip

/** All data loaded for a single trip's detail / map view. */
data class TripData(
    val trip: Trip,
    val gpsPoints: List<GpsPoint>,
    val obdSamples: List<ObdSample>,
    val extendedSamples: List<ObdExtendedSample>,
    val dtcs: List<DtcRecord>,
    /** The trip's vehicle, for capacities/units in the summary. Null if it can't be loaded. */
    val vehicle: Vehicle? = null,
)

/** Aggregates computed by the TripAggregator and written on stop. */
data class TripAggregates(
    val distanceKm: Double?,
    val durationSec: Long?,
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val movingSec: Long?,
    val fuelUsedL: Double?,
    val energyUsedKwh: Double?,
    val avgConsumptionL100: Double?,
    val obdSampleCount: Int,
    val gpsPointCount: Int,
)

/** Single entry point for trip recording and time-series telemetry. */
class TelemetryRepository(
    private val tripDao: TripDao,
    private val gpsPointDao: GpsPointDao,
    private val obdSampleDao: ObdSampleDao,
    private val extendedDao: ObdExtendedSampleDao,
    private val dtcDao: DtcDao,
    // Optional sync wiring. When present, deleting a synced trip leaves a tombstone and kicks a
    // sync so the remote copy is purged instead of being re-imported on the next pull.
    private val vehicleDao: VehicleDao? = null,
    private val deletedTripDao: DeletedTripDao? = null,
    private val onTripDeleted: () -> Unit = {},
) {
    // Trips
    fun observeTrips(vehicleId: Long): Flow<List<Trip>> = tripDao.observeForVehicle(vehicleId)
    fun observeTrip(id: Long): Flow<Trip?> = tripDao.observe(id)
    suspend fun getTrip(id: Long): Trip? = tripDao.getById(id)

    /** Most recent odometer reading (km) from this vehicle's trips, or null if none reported it. */
    suspend fun latestOdometerKm(vehicleId: Long): Double? = obdSampleDao.latestOdometerForVehicle(vehicleId)

    /** Final odometer (km) keyed by trip id, for trips of this vehicle that reported the PID. */
    suspend fun finalOdometerByTrip(vehicleId: Long): Map<Long, Double> =
        obdSampleDao.finalOdometerByVehicle(vehicleId).associate { it.tripId to it.odometerKm }

    /** First/last fuel-tank level (%) keyed by trip id, for estimating fuel usage from the tank drop. */
    suspend fun fuelSpansByTrip(vehicleId: Long): Map<Long, TripFuelSpan> =
        obdSampleDao.fuelSpansByVehicle(vehicleId).associateBy { it.tripId }
    suspend fun startTrip(trip: Trip): Long = tripDao.insert(trip)
    suspend fun updateTrip(trip: Trip) = tripDao.update(trip)
    /**
     * Delete a trip and its time-series (FK cascade). If the trip had been synced to WebDAV,
     * first record a tombstone keyed by its syncId so the next sync purges the remote copy and
     * the pull walk won't re-import it; then nudge a sync. Unsynced trips just vanish locally.
     */
    suspend fun deleteTrip(id: Long) {
        val trip = tripDao.getById(id)
        if (trip != null && trip.syncedAtMillis != null && deletedTripDao != null) {
            val vehicleSyncId = vehicleDao?.getById(trip.vehicleId)?.syncId
            if (vehicleSyncId != null) {
                deletedTripDao.upsert(
                    DeletedTrip(
                        tripSyncId = trip.syncId,
                        vehicleSyncId = vehicleSyncId,
                        startedAtMillis = trip.startedAtMillis,
                        deletedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
        tripDao.deleteById(id)
        onTripDeleted()
    }

    /** A trip still flagged [TripStatus.RECORDING] (left over after a crash), if any. */
    suspend fun recoverableTrip(): Trip? = tripDao.latestWithStatus(TripStatus.RECORDING)

    // Batched sample appends (called by the recorder on flush)
    suspend fun appendGps(points: List<GpsPoint>) = gpsPointDao.insertAll(points)
    suspend fun appendObd(samples: List<ObdSample>) = obdSampleDao.insertAll(samples)
    suspend fun appendExtended(samples: List<ObdExtendedSample>) = extendedDao.insertAll(samples)
    suspend fun appendDtc(records: List<DtcRecord>) = dtcDao.insertAll(records)

    /** Finalize a trip: set its end time, status and computed aggregates. */
    suspend fun finalizeTrip(id: Long, status: TripStatus, endedAtMillis: Long?, agg: TripAggregates) {
        tripDao.finalize(
            id = id,
            endedAtMillis = endedAtMillis,
            status = status,
            distanceKm = agg.distanceKm,
            durationSec = agg.durationSec,
            avgSpeedKmh = agg.avgSpeedKmh,
            maxSpeedKmh = agg.maxSpeedKmh,
            movingSec = agg.movingSec,
            fuelUsedL = agg.fuelUsedL,
            energyUsedKwh = agg.energyUsedKwh,
            avgConsumptionL100 = agg.avgConsumptionL100,
            obdSampleCount = agg.obdSampleCount,
            gpsPointCount = agg.gpsPointCount,
        )
    }

    /**
     * Recompute a finished trip's aggregates from its still-stored samples and persist them. Lets a
     * trip recorded under an older aggregation (e.g. before odometer-based distance) pick up the
     * corrected metrics without re-driving — the per-sample time series is never pruned, so the
     * numbers are fully rebuildable. No-op (returns null) if the trip is gone or still recording.
     *
     * Local-only: it does not re-push an already-synced trip to WebDAV (the remote manifest is
     * written once and not overwritten), so corrected metrics stay on this device.
     */
    suspend fun recomputeAggregates(tripId: Long): TripAggregates? {
        val trip = tripDao.getById(tripId) ?: return null
        if (trip.status == TripStatus.RECORDING) return null
        val data = getTripData(tripId) ?: return null
        val ended = trip.endedAtMillis
            ?: data.obdSamples.lastOrNull()?.timestampMillis
            ?: data.gpsPoints.lastOrNull()?.timestampMillis
            ?: trip.startedAtMillis
        val agg = TripAggregator.aggregate(trip.startedAtMillis, ended, data.gpsPoints, data.obdSamples)
        finalizeTrip(tripId, trip.status, ended, agg)
        return agg
    }

    suspend fun countSamples(tripId: Long): Pair<Int, Int> =
        obdSampleDao.countForTrip(tripId) to gpsPointDao.countForTrip(tripId)

    /** Load everything for one trip's detail / map view. Returns null if the trip is gone. */
    suspend fun getTripData(id: Long): TripData? {
        val trip = tripDao.getById(id) ?: return null
        return TripData(
            trip = trip,
            gpsPoints = gpsPointDao.getForTrip(id),
            obdSamples = obdSampleDao.getForTrip(id),
            extendedSamples = extendedDao.getForTrip(id),
            dtcs = dtcDao.getForTrip(id),
            vehicle = vehicleDao?.getById(trip.vehicleId),
        )
    }
}

package me.crsib.freecartracker.telemetry.data

import kotlinx.coroutines.flow.Flow
import me.crsib.freecartracker.data.dao.VehicleDao
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.data.dao.DeletedTripDao
import me.crsib.freecartracker.telemetry.data.dao.DtcDao
import me.crsib.freecartracker.telemetry.data.dao.GpsPointDao
import me.crsib.freecartracker.telemetry.data.dao.ObdExtendedSampleDao
import me.crsib.freecartracker.telemetry.data.dao.ObdSampleDao
import me.crsib.freecartracker.telemetry.data.dao.TripDao
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

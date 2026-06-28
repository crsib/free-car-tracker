package me.crsib.freecartracker.telemetry.service

import me.crsib.freecartracker.data.repository.VehicleRepository
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.domain.model.TripStatus
import me.crsib.freecartracker.telemetry.DerivedMetrics
import me.crsib.freecartracker.telemetry.DiagLog
import me.crsib.freecartracker.telemetry.TripAggregator
import me.crsib.freecartracker.telemetry.data.TelemetryRepository
import me.crsib.freecartracker.telemetry.data.TripAggregates
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import me.crsib.freecartracker.telemetry.data.entity.Trip
import me.crsib.freecartracker.telemetry.location.LocationFix
import me.crsib.freecartracker.telemetry.location.LocationSource
import me.crsib.freecartracker.telemetry.location.LocationSourceFactory
import me.crsib.freecartracker.telemetry.obd.AdaptivePoller
import me.crsib.freecartracker.telemetry.obd.AdaptivePollSchedule
import me.crsib.freecartracker.telemetry.obd.ObdReading
import me.crsib.freecartracker.telemetry.obd.ObdSession
import me.crsib.freecartracker.telemetry.obd.pid.PidProfileRegistry
import me.crsib.freecartracker.telemetry.obd.pid.Storage
import me.crsib.freecartracker.telemetry.obd.transport.ObdConnectionConfig
import me.crsib.freecartracker.telemetry.obd.transport.ObdTransport
import me.crsib.freecartracker.telemetry.obd.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * App-scoped singleton that owns one recording session: connects OBD, subscribes to GPS,
 * batches samples to the database, and exposes live state to the UI. The foreground
 * [TelemetryService] only keeps the process alive; all work happens here.
 */
class TripRecorder(
    private val telemetryRepo: TelemetryRepository,
    private val vehicleRepo: VehicleRepository,
    private val transportFactory: TransportFactory,
    private val locationFactory: LocationSourceFactory,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Invoked after a trip is finalized as COMPLETED, so the WebDAV sync can upload it. */
    private val onTripCompleted: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes session lifecycle transitions (start/stop/recover) so they can't interleave. */
    private val lifecycleMutex = Mutex()

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _liveState = MutableStateFlow(LiveTelemetry())
    val liveState: StateFlow<LiveTelemetry> = _liveState.asStateFlow()

    private var sessionJob: kotlinx.coroutines.Job? = null
    @Volatile private var tripId: Long? = null
    @Volatile private var startedAt: Long = 0
    @Volatile private var requestedStatus: TripStatus = TripStatus.ABORTED
    @Volatile private var lastFix: LocationFix? = null

    /** When the OBD link first dropped (null while connected); drives auto-end. */
    @Volatile private var obdLostSince: Long? = null

    /** Set when CompanionDeviceManager reports the adapter left BT range — shortens auto-end grace. */
    @Volatile private var presenceLost: Boolean = false

    private val bufferMutex = Mutex()
    private val obdBuffer = mutableListOf<ObdSample>()
    private val gpsBuffer = mutableListOf<GpsPoint>()
    private val extBuffer = mutableListOf<ObdExtendedSample>()

    val isRecording: Boolean
        get() = _recordingState.value != RecordingState.IDLE

    fun start(vehicleId: Long, config: ObdConnectionConfig) {
        scope.launch {
            lifecycleMutex.withLock {
                // A genuinely-running session ignores a duplicate start. A session that stop()
                // already cancelled has isActive == false here, so we fall through and join it —
                // letting its NonCancellable finalize() complete before the new session begins,
                // so the old finalize can't clobber the new session's state.
                val current = sessionJob
                if (current != null && current.isActive) return@withLock
                current?.join()
                requestedStatus = TripStatus.ABORTED
                _recordingState.value = RecordingState.CONNECTING
                sessionJob = scope.launch { runRecording(vehicleId, config) }
            }
        }
    }

    fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                requestedStatus = TripStatus.COMPLETED
                sessionJob?.cancel()
            }
        }
    }

    /** CompanionDeviceManager hint that the adapter left BT range — auto-end sooner if OBD is down. */
    fun onAdapterPresenceLost() { presenceLost = true }

    /** Adapter is back in range; cancel the shortened auto-end grace. */
    fun onAdapterPresenceGained() { presenceLost = false }

    private suspend fun runRecording(vehicleId: Long, config: ObdConnectionConfig) {
        val vehicle = vehicleRepo.getById(vehicleId)
        if (vehicle == null) {
            _recordingState.value = RecordingState.IDLE
            return
        }
        startedAt = clock()
        lastFix = null
        obdLostSince = null
        presenceLost = false
        resetBuffers()
        val id = telemetryRepo.startTrip(
            Trip(
                vehicleId = vehicleId,
                startedAtMillis = startedAt,
                status = TripStatus.RECORDING,
                transport = config.kind,
                profileKey = PidProfileRegistry.select(vehicle).key,
            ),
        )
        tripId = id
        _liveState.value = LiveTelemetry(transport = config.kind)

        try {
            coroutineScope {
                launch { collectGps() }
                if (config.kind != ObdTransportKind.NONE) {
                    launch { collectObd(vehicleId, config) }
                    launch { autoEndWatchdog() }
                } else {
                    _recordingState.value = RecordingState.RECORDING
                }
                launch { flushLoop() }
                launch { elapsedLoop() }
            }
        } finally {
            withContext(NonCancellable) { finalize(requestedStatus) }
        }
    }

    private suspend fun collectObd(vehicleId: Long, config: ObdConnectionConfig) {
        val vehicle = vehicleRepo.getById(vehicleId) ?: return
        val profile = PidProfileRegistry.select(vehicle)
        var backoffMs = INITIAL_BACKOFF_MS
        while (coroutineContext.isActive) {
            var transport: ObdTransport? = null
            try {
                _recordingState.value = RecordingState.CONNECTING
                transport = transportFactory.create(config)
                transport.connect()
                val session = ObdSession(transport)
                session.initialize()
                val supported = runCatching { session.discoverSupportedPids() }.getOrDefault(emptySet())
                val defs = profile.pids.filter { def ->
                    // Keep non-Mode-01 (manufacturer) PIDs and discovery-exempt ones; filter the
                    // rest of Mode 01 by the supported-PID bitmask when discovery succeeded.
                    def.skipDiscoveryGate || def.command.mode != "01" || supported.isEmpty() ||
                        def.command.pid.uppercase() in supported
                }
                val poller = AdaptivePoller(session, AdaptivePollSchedule.from(defs))
                DiagLog.i(TAG, "OBD connected (${config.kind}); supported=${supported.size}; polling ${defs.size}: ${defs.map { it.key }}")
                _recordingState.value = RecordingState.RECORDING
                _liveState.update { it.copy(obdConnected = true) }
                obdLostSince = null
                backoffMs = INITIAL_BACKOFF_MS
                poller.readings().collect { readings -> onObdReadings(readings) }
            } catch (c: CancellationException) {
                runCatching { transport?.close() }
                throw c
            } catch (e: Exception) {
                DiagLog.w(TAG, "OBD session ended; reconnecting in ${backoffMs}ms (${e.javaClass.simpleName}: ${e.message})")
                _recordingState.value = RecordingState.RECONNECTING
                _liveState.update { it.copy(obdConnected = false) }
                if (obdLostSince == null) obdLostSince = clock()
                runCatching { transport?.close() }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * Ends the trip automatically once the OBD link has been down long enough to mean the drive is
     * over (adapter lost power on engine-off, or you walked out of range) rather than a momentary
     * dropout. A CompanionDeviceManager "device left range" hint ([presenceLost]) shortens the
     * grace. Finalizes as COMPLETED via the same path as a manual Stop.
     */
    private suspend fun autoEndWatchdog() {
        while (coroutineContext.isActive) {
            delay(AUTO_END_CHECK_MS)
            val since = obdLostSince ?: continue
            val grace = if (presenceLost) AUTO_END_GRACE_AFTER_DISAPPEAR_MS else AUTO_END_GRACE_MS
            if (clock() - since >= grace) {
                stop()
                return
            }
        }
    }

    private suspend fun collectGps() {
        try {
            collectGpsInternal()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // Location unavailable or permission not granted: record OBD-only, don't fail the trip.
        }
    }

    private suspend fun collectGpsInternal() {
        val source: LocationSource = locationFactory.create()
        if (!source.isAvailable()) return
        source.locations(GPS_INTERVAL_MS).collect { fix ->
            val id = tripId ?: return@collect
            bufferMutex.withLock {
                gpsBuffer += GpsPoint(
                    tripId = id,
                    timestampMillis = fix.timestampMillis,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    altitudeM = fix.altitudeM,
                    speedKmh = fix.speedKmh,
                    bearingDeg = fix.bearingDeg,
                    accuracyM = fix.accuracyM,
                    provider = fix.provider,
                )
            }
            val prev = lastFix
            val added = if (prev != null) {
                DerivedMetrics.haversineKm(prev.latitude, prev.longitude, fix.latitude, fix.longitude)
            } else {
                0.0
            }
            lastFix = fix
            _liveState.update {
                it.copy(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    gpsSpeedKmh = fix.speedKmh,
                    gpsFix = true,
                    distanceKm = it.distanceKm + added,
                )
            }
        }
    }

    private suspend fun onObdReadings(readings: List<ObdReading>) {
        if (readings.isEmpty()) return
        val id = tripId ?: return
        val ts = clock()
        val wide = readings.filter { it.storage is Storage.Wide }.associate { it.key to it.value }
        val extended = readings.filter { it.storage is Storage.Extended }
        val sample = buildObdSample(id, ts, wide)
        bufferMutex.withLock {
            obdBuffer += sample
            extended.forEach {
                extBuffer += ObdExtendedSample(
                    tripId = id,
                    timestampMillis = ts,
                    metricKey = it.key,
                    value = it.value,
                    unit = it.unit,
                )
            }
        }
        val hvV = wide["hvBatteryVoltage"]
        val hvA = wide["hvBatteryCurrent"]
        _liveState.update { state ->
            // Recompute power from the latest known voltage and current, not just this batch — so a
            // refreshed current (or voltage) updates power even if the other half is unchanged.
            val newHvV = hvV ?: state.hvBatteryVoltage
            val newHvA = hvA ?: state.hvBatteryCurrent
            state.copy(
                speedKmh = wide["speedKmh"] ?: state.speedKmh,
                rpm = wide["rpm"] ?: state.rpm,
                throttlePct = wide["throttlePct"] ?: state.throttlePct,
                engineLoadPct = wide["engineLoadPct"] ?: state.engineLoadPct,
                coolantTempC = wide["coolantTempC"] ?: state.coolantTempC,
                controlModuleVoltage = wide["controlModuleVoltage"] ?: state.controlModuleVoltage,
                fuelLevelPct = wide["fuelLevelPct"] ?: state.fuelLevelPct,
                hybridBatteryLifePct = wide["hybridBatteryLifePct"] ?: state.hybridBatteryLifePct,
                hvBatteryVoltage = newHvV,
                hvBatteryCurrent = newHvA,
                hvBatteryPowerKw = if (newHvV != null && newHvA != null) newHvV * newHvA / 1000.0 else state.hvBatteryPowerKw,
                odometerKm = wide["odometerKm"] ?: state.odometerKm,
                obdConnected = true,
                extended = if (extended.isEmpty()) {
                    state.extended
                } else {
                    state.extended + extended.associate { it.key to it.value }
                },
            )
        }
    }

    private fun buildObdSample(tripId: Long, ts: Long, m: Map<String, Double>): ObdSample {
        val l100 = DerivedMetrics.l100(m["fuelRateLph"], m["mafGramsPerSec"], m["speedKmh"])
        val hvV = m["hvBatteryVoltage"]
        val hvA = m["hvBatteryCurrent"]
        return ObdSample(
            tripId = tripId,
            timestampMillis = ts,
            speedKmh = m["speedKmh"],
            rpm = m["rpm"],
            throttlePct = m["throttlePct"],
            engineLoadPct = m["engineLoadPct"],
            runTimeSec = m["runTimeSec"]?.toInt(),
            coolantTempC = m["coolantTempC"],
            timingAdvanceDeg = m["timingAdvanceDeg"],
            intakeAirTempC = m["intakeAirTempC"],
            ambientAirTempC = m["ambientAirTempC"],
            mafGramsPerSec = m["mafGramsPerSec"],
            manifoldPressureKpa = m["manifoldPressureKpa"],
            controlModuleVoltage = m["controlModuleVoltage"],
            fuelLevelPct = m["fuelLevelPct"],
            fuelRateLph = m["fuelRateLph"],
            derivedL100 = l100,
            derivedCo2Gkm = DerivedMetrics.co2GkmFromL100(l100),
            odometerKm = m["odometerKm"],
            hybridBatteryLifePct = m["hybridBatteryLifePct"],
            hvBatteryVoltage = hvV,
            hvBatteryCurrent = hvA,
            hvBatteryPowerKw = if (hvV != null && hvA != null) hvV * hvA / 1000.0 else null,
        )
    }

    private suspend fun flushLoop() {
        while (coroutineContext.isActive) {
            delay(FLUSH_INTERVAL_MS)
            flushBuffers()
        }
    }

    private suspend fun elapsedLoop() {
        while (coroutineContext.isActive) {
            _liveState.update { it.copy(elapsedSec = (clock() - startedAt) / 1000) }
            delay(1000)
        }
    }

    private suspend fun flushBuffers() {
        val obd: List<ObdSample>
        val gps: List<GpsPoint>
        val ext: List<ObdExtendedSample>
        bufferMutex.withLock {
            obd = obdBuffer.toList(); obdBuffer.clear()
            gps = gpsBuffer.toList(); gpsBuffer.clear()
            ext = extBuffer.toList(); extBuffer.clear()
        }
        if (obd.isNotEmpty()) telemetryRepo.appendObd(obd)
        if (gps.isNotEmpty()) telemetryRepo.appendGps(gps)
        if (ext.isNotEmpty()) telemetryRepo.appendExtended(ext)
    }

    private suspend fun finalize(status: TripStatus) {
        val id = tripId
        if (id == null) {
            _recordingState.value = RecordingState.IDLE
            return
        }
        flushBuffers()
        val ended = clock()
        val data = telemetryRepo.getTripData(id)
        val agg = if (data != null) {
            TripAggregator.aggregate(startedAt, ended, data.gpsPoints, data.obdSamples)
        } else {
            EMPTY_AGGREGATES
        }
        telemetryRepo.finalizeTrip(id, status, ended, agg)
        tripId = null
        _liveState.value = LiveTelemetry()
        _recordingState.value = RecordingState.IDLE
        if (status == TripStatus.COMPLETED) runCatching { onTripCompleted() }
    }

    private suspend fun resetBuffers() = bufferMutex.withLock {
        obdBuffer.clear(); gpsBuffer.clear(); extBuffer.clear()
    }

    /**
     * Finalize any trip left in RECORDING from a previous process death. Safe to call on app
     * start; does nothing if a recording is currently active.
     */
    fun recoverIncompleteTrips() {
        scope.launch {
            // Guard the whole recovery: a DB failure here must not crash app startup.
            runCatching {
                lifecycleMutex.withLock {
                    // Don't touch a session that's starting/running concurrently (e.g. app launched
                    // straight into recording); its RECORDING row is not an orphan.
                    if (sessionJob?.isActive == true) return@withLock
                    val orphan = telemetryRepo.recoverableTrip() ?: return@withLock
                    val data = telemetryRepo.getTripData(orphan.id)
                    val ended = data?.gpsPoints?.lastOrNull()?.timestampMillis
                        ?: data?.obdSamples?.lastOrNull()?.timestampMillis
                        ?: orphan.startedAtMillis
                    val agg = if (data != null) {
                        TripAggregator.aggregate(orphan.startedAtMillis, ended, data.gpsPoints, data.obdSamples)
                    } else {
                        EMPTY_AGGREGATES
                    }
                    telemetryRepo.finalizeTrip(orphan.id, TripStatus.ABORTED, ended, agg)
                }
            }
        }
    }

    private companion object {
        const val TAG = "TripRecorder"
        const val GPS_INTERVAL_MS = 1000L
        const val FLUSH_INTERVAL_MS = 2000L
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 15_000L

        /** Auto-end watchdog cadence and grace periods (sustained OBD loss → finalize the trip). */
        const val AUTO_END_CHECK_MS = 15_000L
        const val AUTO_END_GRACE_MS = 180_000L
        const val AUTO_END_GRACE_AFTER_DISAPPEAR_MS = 30_000L
        val EMPTY_AGGREGATES = TripAggregates(
            distanceKm = null, durationSec = null, avgSpeedKmh = null, maxSpeedKmh = null,
            movingSec = null, fuelUsedL = null, energyUsedKwh = null, avgConsumptionL100 = null,
            obdSampleCount = 0, gpsPointCount = 0,
        )
    }
}

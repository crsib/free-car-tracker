package me.crsib.freecartracker.ui.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.data.repository.VehicleRepository
import me.crsib.freecartracker.domain.model.ObdTransportKind
import me.crsib.freecartracker.telemetry.DiagLog
import me.crsib.freecartracker.telemetry.companion.ObdCompanionManager
import me.crsib.freecartracker.telemetry.data.TelemetrySettingsStore
import me.crsib.freecartracker.telemetry.location.LocationSourceFactory
import me.crsib.freecartracker.telemetry.obd.ObdSession
import me.crsib.freecartracker.telemetry.obd.pid.StandardPidProfile
import me.crsib.freecartracker.telemetry.obd.scan.DiagnosticFiles
import me.crsib.freecartracker.telemetry.obd.scan.DidDecoders
import me.crsib.freecartracker.telemetry.obd.scan.DidProbeResult
import me.crsib.freecartracker.telemetry.obd.scan.DidResponseKind
import me.crsib.freecartracker.telemetry.obd.scan.DidScanCsvExporter
import me.crsib.freecartracker.telemetry.obd.scan.DidScanConfig
import me.crsib.freecartracker.telemetry.obd.scan.DidScanEvent
import me.crsib.freecartracker.telemetry.obd.scan.DidScanMemoryStore
import me.crsib.freecartracker.telemetry.obd.scan.DidScanner
import me.crsib.freecartracker.telemetry.obd.scan.DidWatchCsv
import me.crsib.freecartracker.telemetry.obd.scan.DidWatchEvent
import me.crsib.freecartracker.telemetry.obd.scan.DidWatchSample
import me.crsib.freecartracker.telemetry.obd.scan.EcuClassification
import me.crsib.freecartracker.telemetry.obd.scan.InitMapCsvExporter
import me.crsib.freecartracker.telemetry.obd.scan.InitRecordEvent
import me.crsib.freecartracker.telemetry.obd.scan.InitResponse
import me.crsib.freecartracker.telemetry.obd.scan.InitSequenceRecorder
import me.crsib.freecartracker.telemetry.obd.scan.ReferencePids
import me.crsib.freecartracker.telemetry.obd.scan.LixiangEcuSeeds
import me.crsib.freecartracker.telemetry.obd.scan.RefReading
import me.crsib.freecartracker.telemetry.obd.scan.ScanContext
import me.crsib.freecartracker.telemetry.obd.scan.StandardPidScanner
import me.crsib.freecartracker.telemetry.obd.scan.StdPidCsvExporter
import me.crsib.freecartracker.telemetry.obd.scan.StdPidScanEvent
import me.crsib.freecartracker.telemetry.obd.scan.ScanStates
import me.crsib.freecartracker.telemetry.obd.scan.parseWatchTargets
import me.crsib.freecartracker.data.webdav.WebDavPaths
import me.crsib.freecartracker.data.webdav.WebDavSyncManager
import me.crsib.freecartracker.telemetry.obd.transport.ObdConnectionConfig
import me.crsib.freecartracker.telemetry.obd.transport.ObdTransport
import me.crsib.freecartracker.telemetry.obd.transport.TransportFactory
import java.io.File

data class BtDevice(val name: String, val address: String)

data class ObdConnUiState(
    val config: ObdConnectionConfig = ObdConnectionConfig(),
    val bonded: List<BtDevice> = emptyList(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val savedAt: Long = 0,
    val autoStartEnabled: Boolean = false,
    /** One-shot: a system association dialog the screen must launch from an Activity. */
    val pendingAssociation: IntentSender? = null,
    val autoStartError: String? = null,
    val vehicles: List<Vehicle> = emptyList(),
    /** Vehicle bound to this adapter, or null to use the currently-selected one. */
    val adapterVehicleId: Long? = null,
    // --- UDS DID scan (manufacturer PID discovery) ---
    val scanning: Boolean = false,
    /** Human-readable progress/result line for the DID scan. */
    val scanStatus: String? = null,
    /** Number of DIDs that returned data so far this scan. */
    val scanHits: Int = 0,
    /** Scan completion in 0f..1f (DID-space covered / total), or null when no scan is in progress. */
    val scanProgress: Float? = null,
    /** Filesystem path of the exported CSV once a scan has produced one. */
    val scanCsvPath: String? = null,
    /** Vehicle state the scan is tagged with (skip-memory is scoped to it). */
    val scanState: String = ScanStates.PARKED,
    /** Diagnostic session for the scan: DEFAULT or EXTENDED. */
    val scanSession: String = ScanContext.SESSION_DEFAULT,
    // --- Standard mode-01 PID sweep (pin manufacturer EV PIDs on a real car) ---
    val stdSweeping: Boolean = false,
    val stdSweepStatus: String? = null,
    /** Sweep completion in 0f..1f (probed / total), or null when no sweep is in progress. */
    val stdSweepProgress: Float? = null,
    val stdSweepCsvPath: String? = null,
    // --- Init-sequence recorder (capture real car handshake for honeypot replay) ---
    val initRecording: Boolean = false,
    val initStatus: String? = null,
    val initCsvPath: String? = null,
    // --- Force-sync of locally-saved diagnostic CSVs to WebDAV ---
    val diagSyncing: Boolean = false,
    val diagSyncStatus: String? = null,
    // --- Upload of the persisted telemetry diagnostics log to WebDAV ---
    val logUploading: Boolean = false,
    val logUploadStatus: String? = null,
    // --- Live DID watch (correlation aid) ---
    val watching: Boolean = false,
    /** Free-text watch list, "HEADER DID" per line; seeded from the last scan's hits. */
    val watchInput: String = "",
    /** Current value of each watched DID, refreshed every poll. */
    val watchRows: List<DidWatchRow> = emptyList(),
    /** Current value of each reference PID (speed, RPM…), refreshed every poll. */
    val watchRefRows: List<DidWatchRow> = emptyList(),
    /** Also log coolant + throttle alongside speed/RPM. */
    val watchLogExtra: Boolean = false,
    /** Path of the saved watch time-series CSV once a session ends. */
    val watchLogPath: String? = null,
    val watchError: String? = null,
)

/** One live-watch row: a DID and its latest value, refreshed each poll. */
data class DidWatchRow(
    val key: String,
    val label: String,
    val value: String,
    val hint: String,
    val round: Int,
)

class ObdConnectionViewModel(
    private val appContext: Context,
    private val settings: TelemetrySettingsStore,
    private val transportFactory: TransportFactory,
    private val locationFactory: LocationSourceFactory,
    private val companion: ObdCompanionManager,
    private val vehicleRepo: VehicleRepository,
    private val webDav: WebDavSyncManager,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ObdConnUiState(
            config = settings.loadConnection(),
            autoStartEnabled = settings.autoStartEnabled,
            adapterVehicleId = settings.adapterVehicleId,
        ),
    )
    val state: StateFlow<ObdConnUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            vehicleRepo.observeActive().collect { list ->
                _state.update { it.copy(vehicles = list) }
            }
        }
    }

    /** Bind this adapter to a vehicle (or null to follow the current selection). Persisted at once. */
    fun setAdapterVehicle(id: Long?) {
        settings.adapterVehicleId = id
        _state.update { it.copy(adapterVehicleId = id) }
    }

    private fun updateConfig(block: (ObdConnectionConfig) -> ObdConnectionConfig) =
        _state.update { it.copy(config = block(it.config), testResult = null) }

    fun setKind(kind: ObdTransportKind) = updateConfig { it.copy(kind = kind) }
    fun setWifiHost(host: String) = updateConfig { it.copy(wifiHost = host) }
    fun setWifiPort(port: String) = updateConfig { it.copy(wifiPort = port.toIntOrNull() ?: it.wifiPort) }
    fun selectDevice(device: BtDevice) =
        updateConfig { it.copy(deviceAddress = device.address, deviceName = device.name) }
    fun setBleService(uuid: String) = updateConfig { it.copy(bleServiceUuid = uuid) }
    fun setBleNotify(uuid: String) = updateConfig { it.copy(bleNotifyUuid = uuid) }
    fun setBleWrite(uuid: String) = updateConfig { it.copy(bleWriteUuid = uuid) }

    fun save() {
        settings.saveConnection(_state.value.config)
        _state.update { it.copy(savedAt = System.currentTimeMillis()) }
    }

    /**
     * Toggle auto-start recording. Enabling requires a saved BT-Classic adapter and, the first
     * time, runs the CompanionDeviceManager association dialog (a one-time system consent). Once
     * associated, the OS wakes the app and starts recording whenever the adapter comes into range.
     */
    fun setAutoStart(enabled: Boolean) = if (enabled) enableAutoStart() else disableAutoStart()

    private fun enableAutoStart() {
        val config = _state.value.config
        val address = config.deviceAddress
        if (config.kind != ObdTransportKind.BT_CLASSIC || address.isNullOrBlank()) {
            _state.update { it.copy(autoStartError = "Pick a paired Bluetooth adapter first.") }
            return
        }
        if (!companion.isAvailable()) {
            _state.update { it.copy(autoStartError = "This device doesn't support companion auto-start.") }
            return
        }
        // Persist the chosen adapter before wiring the OS to it.
        settings.saveConnection(config)
        _state.update { it.copy(autoStartError = null) }
        if (companion.isAssociated(address)) {
            finishEnable(address)
            return
        }
        companion.associate(address) { result ->
            when (result) {
                is ObdCompanionManager.AssociateResult.Pending ->
                    _state.update { it.copy(pendingAssociation = result.intentSender) }
                is ObdCompanionManager.AssociateResult.Created -> finishEnable(address)
                is ObdCompanionManager.AssociateResult.Failed ->
                    _state.update { it.copy(autoStartError = result.reason?.toString() ?: "Association failed.") }
            }
        }
    }

    private fun finishEnable(address: String) {
        settings.autoStartEnabled = true
        companion.startObserving(address)
        DiagLog.i("ObdAutoStart", "finishEnable: associated + observing $address")
        _state.update { it.copy(autoStartEnabled = true, autoStartError = null) }
    }

    private fun disableAutoStart() {
        settings.autoStartEnabled = false
        _state.value.config.deviceAddress?.let { companion.disassociate(it) }
        _state.update { it.copy(autoStartEnabled = false, autoStartError = null) }
    }

    /** The screen has launched [ObdConnUiState.pendingAssociation]; clear it so it fires once. */
    fun consumePendingAssociation() = _state.update { it.copy(pendingAssociation = null) }

    /** The user dismissed the system association dialog without confirming. */
    fun onAssociationCancelled() =
        _state.update { it.copy(autoStartError = "Auto-start setup was cancelled.") }

    @SuppressLint("MissingPermission") // caller requests BLUETOOTH_CONNECT before invoking
    fun refreshBondedDevices() {
        val devices = runCatching {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
            manager?.adapter?.bondedDevices.orEmpty().map {
                BtDevice(name = it.name ?: it.address, address = it.address)
            }
        }.getOrDefault(emptyList())
        _state.update { it.copy(bonded = devices) }
    }

    fun testConnection() {
        if (_state.value.testing) return
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val config = _state.value.config
            val result = withContext(Dispatchers.IO) { runTest(config) }
            _state.update { it.copy(testing = false, testResult = result) }
        }
    }

    private suspend fun runTest(config: ObdConnectionConfig): String {
        var transport: ObdTransport? = null
        return try {
            transport = transportFactory.create(config)
            transport.connect()
            val session = ObdSession(transport)
            session.initialize()
            val supported = runCatching { session.discoverSupportedPids() }.getOrDefault(emptySet())
            val rpm = session.query(StandardPidProfile.pids.first { it.key == "rpm" }).firstOrNull()
            val rpmText = rpm?.value?.toInt()?.toString() ?: "n/a"
            "Connected. Supported PIDs: ${supported.size}. RPM: $rpmText. " +
                "Location: ${locationFactory.create().name}."
        } catch (e: Exception) {
            "Failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { transport?.close() }
        }
    }

    private var scanJob: Job? = null
    private val didMemory by lazy { DidScanMemoryStore(File(appContext.filesDir, "did-scan-memory.json")) }

    fun setScanState(state: String) = _state.update { it.copy(scanState = state) }
    fun setScanSession(session: String) = _state.update { it.copy(scanSession = session) }

    /**
     * Full DID sweep: find live ECUs, then sweep `22 xxxx`, skipping DIDs already known dead in the
     * current [ScanContext] (so re-scans finish fast). Hits export to CSV (+ WebDAV); the run's
     * classifications are merged into the skip-memory.
     */
    fun scanDids() = runScan(onlyInteresting = false)

    /**
     * Targeted re-probe of just the "interesting" DIDs (every hit + "maybe later" across contexts) in
     * the current state — the fast way to catch DIDs that only answer while driving/charging.
     */
    fun reprobeInteresting() = runScan(onlyInteresting = true)

    private fun runScan(onlyInteresting: Boolean) {
        if (_state.value.scanning) return
        val ctx = ScanContext(_state.value.scanState, _state.value.scanSession)
        val memory = didMemory.load()
        val interesting = if (onlyInteresting) didMemory.interestingDids(memory) else null
        if (onlyInteresting && interesting.isNullOrEmpty()) {
            _state.update { it.copy(scanStatus = "No DIDs to re-probe yet — run a full scan first.") }
            return
        }
        _state.update {
            it.copy(scanning = true, scanStatus = "Connecting…", scanHits = 0, scanProgress = 0f, scanCsvPath = null)
        }
        scanJob = viewModelScope.launch {
            val config = _state.value.config
            val hits = mutableListOf<DidProbeResult>()
            val classifications = mutableListOf<EcuClassification>()
            var transport: ObdTransport? = null
            var status = "Scan finished."
            var skipped = 0
            // Total DID-space to cover (ECUs × DIDs per ECU), learned from the Started event; the
            // denominator for the progress fraction. 0 until Started arrives, guarding the divide.
            var total = 0
            try {
                withContext(Dispatchers.IO) {
                    transport = transportFactory.create(config).also { it.connect() }
                    val session = ObdSession(transport!!)
                    session.initialize()
                    val scanner = DidScanner(session.openRaw())

                    setStatus("Discovering ECUs…")
                    val live = scanner.discoverEcus()
                    val ecus = live.takeIf { it.isNotEmpty() }?.map { it.ecu } ?: LixiangEcuSeeds.standardPhysical
                    setStatus("Found ${ecus.size} ECU(s). ${if (onlyInteresting) "Re-probing ${interesting!!.size} DIDs" else "Sweeping"}…")

                    val scanConfig = DidScanConfig(
                        ecus = ecus,
                        interProbeDelayMs = 30,
                        maxProbes = 65_536,
                        sessionControl = if (ctx.session == ScanContext.SESSION_EXTENDED) "10 03" else null,
                        skip = if (onlyInteresting) ({ _, _ -> false }) else didMemory.skipPredicate(memory, ctx),
                        dids = interesting,
                    )
                    scanner.scan(scanConfig).collect { event ->
                        when (event) {
                            is DidScanEvent.Started -> total = event.totalEcus * event.didCount
                            is DidScanEvent.Hit -> {
                                hits += event.result
                                _state.update { it.copy(scanHits = hits.size) }
                            }
                            is DidScanEvent.EcuStarted ->
                                setStatus("Scanning ${event.ecu.name} (${event.ecu.requestHeader})…")
                            is DidScanEvent.Progress -> _state.update {
                                val covered = event.probed + event.skipped
                                it.copy(
                                    scanStatus = "${event.ecu.name}: ${event.probed} probed, ${event.hits} hits",
                                    scanProgress = if (total > 0) (covered.toFloat() / total).coerceIn(0f, 1f) else null,
                                )
                            }
                            is DidScanEvent.EcuClassified -> classifications += event.classification
                            is DidScanEvent.Finished -> skipped = event.skipped
                            else -> Unit
                        }
                    }
                }
                status = "Done: ${hits.size} DIDs found" + if (skipped > 0) " ($skipped skipped)." else "."
            } catch (e: CancellationException) {
                status = "Stopped. ${hits.size} DIDs found so far."
                throw e
            } catch (e: Exception) {
                status = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                withContext(NonCancellable) {
                    // Persist what we learned, save + upload hits, all surviving cancellation.
                    runCatching { didMemory.merge(memory, ctx, classifications) }
                    val saved = if (hits.isNotEmpty()) saveAndUpload(hits) else null
                    runCatching { transport?.close() }
                    val seed = if (hits.isNotEmpty() && _state.value.watchInput.isBlank()) hitsToWatchText(hits) else null
                    _state.update {
                        it.copy(
                            scanning = false,
                            scanStatus = status + (saved?.note ?: ""),
                            scanProgress = null,
                            scanCsvPath = saved?.localPath ?: it.scanCsvPath,
                            watchInput = seed ?: it.watchInput,
                        )
                    }
                }
            }
        }
    }

    /** Stop an in-progress scan; any DIDs already found are still exported. */
    fun cancelScan() {
        scanJob?.cancel()
    }

    private fun setStatus(text: String) = _state.update { it.copy(scanStatus = text) }

    private data class SavedScan(val localPath: String, val note: String)

    /** Write the CSV to local storage, then push it to WebDAV when configured. */
    private suspend fun saveAndUpload(hits: List<DidProbeResult>): SavedScan {
        val csv = DidScanCsvExporter.export(hits)
        val dir = File(appContext.filesDir, DiagnosticFiles.DID_SCANS).apply { mkdirs() }
        val file = File(dir, "did-scan-${System.currentTimeMillis()}.csv")
        file.writeBytes(csv)

        if (!webDav.config().isConfigured) return SavedScan(file.absolutePath, "")
        val note = webDav.uploadDidScan(resolveVehicleSyncId(), file.name, csv).fold(
            onSuccess = { " · uploaded to WebDAV" },
            onFailure = { e -> " · WebDAV upload failed: ${e.message ?: e.javaClass.simpleName}" },
        )
        return SavedScan(file.absolutePath, note)
    }

    private var stdSweepJob: Job? = null

    /**
     * Sweep standard mode-01 PIDs (`01 00`..`01 FF`) on the connected car and export the responding
     * ones + raw bytes to CSV (local + WebDAV). Use on a real vehicle to pin a metric's PID when its
     * meaning is known but the PID number isn't (e.g. find the PID whose value decodes to pack volts).
     */
    fun sweepStandardPids() {
        if (_state.value.stdSweeping) return
        _state.update {
            it.copy(stdSweeping = true, stdSweepStatus = "Connecting…", stdSweepProgress = 0f, stdSweepCsvPath = null)
        }
        stdSweepJob = viewModelScope.launch {
            val config = _state.value.config
            val results = mutableListOf<me.crsib.freecartracker.telemetry.obd.scan.StdPidResult>()
            var transport: ObdTransport? = null
            var status = "Sweep finished."
            try {
                withContext(Dispatchers.IO) {
                    transport = transportFactory.create(config).also { it.connect() }
                    val session = ObdSession(transport!!)
                    session.initialize()
                    StandardPidScanner(session.openRaw()).sweep().collect { event ->
                        when (event) {
                            is StdPidScanEvent.Hit -> results += event.result
                            is StdPidScanEvent.Progress -> _state.update {
                                it.copy(
                                    stdSweepStatus = "Probing 01${"%02X".format(event.pid)} — ${event.supported} supported",
                                    stdSweepProgress = (event.probed.toFloat() / STD_SWEEP_TOTAL).coerceIn(0f, 1f),
                                )
                            }
                            is StdPidScanEvent.Finished -> results.also { /* full set captured via Hit */ }
                        }
                    }
                }
                status = "Done: ${results.size} PIDs respond."
            } catch (e: CancellationException) {
                status = "Stopped. ${results.size} PIDs so far."
                throw e
            } catch (e: Exception) {
                status = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                withContext(NonCancellable) {
                    runCatching { transport?.close() }
                    val saved = if (results.isNotEmpty()) {
                        runCatching { saveAndUploadCsv(StdPidCsvExporter.export(results), DiagnosticFiles.STD_PID_SWEEPS, "stdpid") }.getOrNull()
                    } else null
                    _state.update {
                        it.copy(
                            stdSweeping = false,
                            stdSweepStatus = status + (saved?.note ?: ""),
                            stdSweepProgress = null,
                            stdSweepCsvPath = saved?.localPath ?: it.stdSweepCsvPath,
                        )
                    }
                }
            }
        }
    }

    /** Stop an in-progress standard-PID sweep; PIDs found so far are still exported. */
    fun cancelStdSweep() {
        stdSweepJob?.cancel()
    }

    private var initJob: Job? = null

    /**
     * Record the real car's responses to the OBD init/identification sequence (supported-PID
     * bitmasks, `22 1201`, VIN) and export the "init map" CSV (local + WebDAV). Feed it to the
     * honeypot replay so Car Scanner's experimental Li profile engages offline.
     */
    fun recordInitSequence() {
        if (_state.value.initRecording) return
        _state.update { it.copy(initRecording = true, initStatus = "Connecting…", initCsvPath = null) }
        initJob = viewModelScope.launch {
            val config = _state.value.config
            val responses = mutableListOf<InitResponse>()
            var transport: ObdTransport? = null
            var status = "Init recorded."
            try {
                withContext(Dispatchers.IO) {
                    transport = transportFactory.create(config).also { it.connect() }
                    val session = ObdSession(transport!!)
                    session.initialize()
                    InitSequenceRecorder(session.openRaw()).record().collect { event ->
                        when (event) {
                            is InitRecordEvent.Probed -> {
                                responses += event.response
                                _state.update { it.copy(initStatus = "Recording ${event.response.label} (${event.index}/${event.total})") }
                            }
                            is InitRecordEvent.Finished -> Unit
                        }
                    }
                }
                status = "Done: ${responses.size} init responses captured."
            } catch (e: CancellationException) {
                status = "Stopped. ${responses.size} captured."
                throw e
            } catch (e: Exception) {
                status = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                withContext(NonCancellable) {
                    runCatching { transport?.close() }
                    val saved = if (responses.isNotEmpty()) {
                        runCatching { saveAndUploadCsv(InitMapCsvExporter.export(responses), DiagnosticFiles.INIT_MAPS, "init") }.getOrNull()
                    } else null
                    _state.update {
                        it.copy(
                            initRecording = false,
                            initStatus = status + (saved?.note ?: ""),
                            initCsvPath = saved?.localPath ?: it.initCsvPath,
                        )
                    }
                }
            }
        }
    }

    /** Stop an in-progress init recording; responses captured so far are still exported. */
    fun cancelInitRecording() {
        initJob?.cancel()
    }

    /**
     * Re-upload any locally-saved diagnostic CSVs that aren't on WebDAV yet — the retry for scans/
     * sweeps/recordings whose inline upload failed offline (e.g. captured in a parking lot, synced
     * back at home). Skips files already on the server, so it's safe to tap repeatedly.
     */
    fun syncDiagnosticFiles() {
        if (_state.value.diagSyncing) return
        _state.update { it.copy(diagSyncing = true, diagSyncStatus = "Syncing…") }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                if (!webDav.config().isConfigured) return@withContext "WebDAV not configured."
                val vehicleSyncId = resolveVehicleSyncId()
                var uploaded = 0
                var alreadyThere = 0
                var failed = 0
                for (sub in DiagnosticFiles.allSubdirs) {
                    val files = File(appContext.filesDir, sub)
                        .listFiles { f -> f.isFile && f.name.endsWith(".csv") }.orEmpty()
                    for (f in files) {
                        webDav.uploadDidScanIfMissing(vehicleSyncId, f.name, f.readBytes()).fold(
                            onSuccess = { if (it) uploaded++ else alreadyThere++ },
                            onFailure = { failed++ },
                        )
                    }
                }
                if (uploaded + alreadyThere + failed == 0) "No diagnostic files to sync."
                else "Synced $uploaded file(s); $alreadyThere already on server" +
                    if (failed > 0) "; $failed failed (no connection?)." else "."
            }
            _state.update { it.copy(diagSyncing = false, diagSyncStatus = status) }
        }
    }

    /** Upload the persisted telemetry diagnostics log (current + rotated backup) to WebDAV. */
    fun uploadDiagnosticsLog() {
        if (_state.value.logUploading) return
        _state.update { it.copy(logUploading = true, logUploadStatus = "Uploading…") }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                if (!webDav.config().isConfigured) return@withContext "WebDAV not configured."
                val files = File(appContext.filesDir, DiagnosticFiles.TELEMETRY_LOGS)
                    .listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
                if (files.isEmpty()) return@withContext "No diagnostics log yet."
                val syncId = resolveVehicleSyncId()
                val stamp = System.currentTimeMillis()
                var uploaded = 0
                var failed = 0
                for (f in files) {
                    webDav.uploadDidScan(syncId, "telemetry-$stamp-${f.name}", f.readBytes()).fold(
                        onSuccess = { uploaded++ },
                        onFailure = { failed++ },
                    )
                }
                if (failed == 0) "Uploaded diagnostics log ($uploaded file) to WebDAV."
                else "Uploaded $uploaded; $failed failed (no connection?)."
            }
            _state.update { it.copy(logUploading = false, logUploadStatus = status) }
        }
    }

    /** Write a CSV to a subdir of files and push to WebDAV when configured; returns local path + note. */
    private suspend fun saveAndUploadCsv(csv: ByteArray, subdir: String, prefix: String): SavedScan {
        val dir = File(appContext.filesDir, subdir).apply { mkdirs() }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.csv")
        file.writeBytes(csv)
        if (!webDav.config().isConfigured) return SavedScan(file.absolutePath, "")
        val note = webDav.uploadDidScan(resolveVehicleSyncId(), file.name, csv).fold(
            onSuccess = { " · uploaded to WebDAV" },
            onFailure = { e -> " · WebDAV upload failed: ${e.message ?: e.javaClass.simpleName}" },
        )
        return SavedScan(file.absolutePath, note)
    }

    /** The car this scan belongs to: the adapter's bound vehicle, else the only vehicle, else none. */
    private fun resolveVehicleSyncId(): String? {
        val vehicles = _state.value.vehicles
        val bound = _state.value.adapterVehicleId
        return (vehicles.firstOrNull { it.id == bound } ?: vehicles.singleOrNull())?.syncId
    }

    private fun hitsToWatchText(hits: List<DidProbeResult>): String =
        hits.filter { it.isHit }.take(MAX_WATCH_SEED)
            .joinToString("\n") { "${it.ecu.requestHeader} ${it.didHex}" }

    private var watchJob: Job? = null

    fun setWatchInput(text: String) = _state.update { it.copy(watchInput = text, watchError = null) }
    fun setWatchLogExtra(on: Boolean) = _state.update { it.copy(watchLogExtra = on) }

    /**
     * Start polling the watch DIDs plus standard reference PIDs (speed, RPM, and optionally coolant/
     * throttle), streaming live values and writing a synchronized time-series CSV (local + WebDAV) so
     * you can correlate a DID's payload against, e.g., speed. Runs exclusively, like the scan.
     */
    fun startWatch() {
        if (_state.value.watching) return
        val targets = parseWatchTargets(_state.value.watchInput)
        if (targets.isEmpty()) {
            _state.update { it.copy(watchError = "Enter DIDs as \"HEADER DID\", one per line (e.g. 7E8 2002).") }
            return
        }
        val refPids = ReferencePids.core + if (_state.value.watchLogExtra) ReferencePids.optional else emptyList()
        val targetKeys = targets.map { it.key }
        _state.update {
            it.copy(watching = true, watchError = null, watchRows = emptyList(), watchRefRows = emptyList(), watchLogPath = null)
        }
        watchJob = viewModelScope.launch {
            val didRows = LinkedHashMap<String, DidWatchRow>()
            val refRows = LinkedHashMap<String, DidWatchRow>()
            val latestDid = HashMap<String, String>()
            val latestRef = HashMap<String, Double?>()
            val log = StringBuilder(DidWatchCsv.header(refPids, targetKeys)).append('\n')
            var transport: ObdTransport? = null
            try {
                withContext(Dispatchers.IO) {
                    transport = transportFactory.create(_state.value.config).also { it.connect() }
                    val session = ObdSession(transport!!)
                    session.initialize()
                    DidScanner(session.openRaw()).watch(targets, refPids).collect { event ->
                        when (event) {
                            is DidWatchEvent.Did -> {
                                didRows[event.sample.target.key] = event.sample.toRow()
                                latestDid[event.sample.target.key] =
                                    if (event.sample.result.isHit) event.sample.result.payloadHex else ""
                                _state.update { it.copy(watchRows = didRows.values.toList()) }
                            }
                            is DidWatchEvent.Ref -> {
                                latestRef[event.reading.pidKey] = event.reading.value
                                refRows[event.reading.pidKey] = event.reading.toRow()
                                _state.update { it.copy(watchRefRows = refRows.values.toList()) }
                            }
                            is DidWatchEvent.RoundDone ->
                                log.append(
                                    DidWatchCsv.row(System.currentTimeMillis(), latestRef, latestDid, refPids, targetKeys),
                                ).append('\n')
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(watchError = "Watch failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                withContext(NonCancellable) {
                    runCatching { transport?.close() }
                    val saved = runCatching { saveAndUploadWatch(log.toString().toByteArray()) }.getOrNull()
                    _state.update { it.copy(watching = false, watchLogPath = saved ?: it.watchLogPath) }
                }
            }
        }
    }

    /** Stop a running live watch. The time-series CSV is saved and uploaded on stop. */
    fun stopWatch() {
        watchJob?.cancel()
    }

    /** Save the watch CSV locally and push to WebDAV (when configured); returns the local path. */
    private suspend fun saveAndUploadWatch(csv: ByteArray): String {
        val dir = File(appContext.filesDir, DiagnosticFiles.WATCH_LOGS).apply { mkdirs() }
        val file = File(dir, "watch-${System.currentTimeMillis()}.csv")
        file.writeBytes(csv)
        if (webDav.config().isConfigured) {
            webDav.uploadDidScan(resolveVehicleSyncId(), file.name, csv)
        }
        return file.absolutePath
    }

    private fun RefReading.toRow(): DidWatchRow {
        val v = value?.let { "${num1(it)} $unit" } ?: "—"
        return DidWatchRow(pidKey, label, v, "", 0)
    }

    private fun num1(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    private fun DidWatchSample.toRow(): DidWatchRow {
        val label = "${target.ecu.requestHeader} ${target.didHex}"
        return if (result.kind == DidResponseKind.POSITIVE) {
            DidWatchRow(target.key, label, result.payloadHex.ifEmpty { "(empty)" }, DidDecoders.hint(result.payloadHex), round)
        } else {
            val v = when (result.kind) {
                DidResponseKind.NEGATIVE -> "neg ${result.negativeCode?.let { "%02X".format(it) } ?: "?"}"
                else -> result.kind.name.lowercase()
            }
            DidWatchRow(target.key, label, v, "", round)
        }
    }

    private companion object {
        const val MAX_WATCH_SEED = 16

        /** Standard mode-01 PID space swept (`01 00`..`01 FF`) — the denominator for sweep progress. */
        const val STD_SWEEP_TOTAL = 0x100
    }
}

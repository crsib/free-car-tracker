package me.crsib.freecartracker.telemetry.obd

import com.github.eltonvs.obd.command.control.AvailablePIDsCommand
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import kotlinx.coroutines.CancellationException
import me.crsib.freecartracker.telemetry.obd.pid.PidDefinition
import me.crsib.freecartracker.telemetry.obd.scan.RawObd
import me.crsib.freecartracker.telemetry.obd.scan.StreamRawObd
import me.crsib.freecartracker.telemetry.obd.transport.ObdTransport

/**
 * Wraps an already-connected [ObdTransport] with the OBD library connection and ELM327 init,
 * and exposes higher-level reads. Construct after [ObdTransport.connect] has succeeded.
 */
class ObdSession(
    private val transport: ObdTransport,
    private val initializer: Elm327Initializer = Elm327Initializer(),
) {
    private val connection = ObdDeviceConnection(transport.input, transport.output)

    suspend fun initialize() = initializer.initialize(connection)

    /** Query one PID. Returns null when the ECU has no data or the value isn't numeric. */
    suspend fun query(def: PidDefinition): ObdReading? = try {
        val resp = connection.run(def.command, maxRetries = MAX_RETRIES)
        val value = resp.value.toDoubleOrNull() ?: return null
        ObdReading(
            key = def.key,
            value = value,
            unit = def.unit.ifEmpty { resp.unit },
            storage = def.storage,
        )
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    /**
     * A raw ELM327 channel over the same transport, for the UDS DID scanner
     * ([me.crsib.freecartracker.telemetry.obd.scan.DidScanner]). Unlike [query] this bypasses the
     * library's response validation so negative/no-data replies are observable. Use exclusively —
     * do not poll via [query] while a raw scan is running on the same adapter.
     */
    fun openRaw(): RawObd = StreamRawObd(transport.input, transport.output)

    /** Discover supported Mode 01 PIDs via the AvailablePIDs bitmask ranges. */
    suspend fun discoverSupportedPids(): Set<String> {
        val responses = buildList {
            for ((range, base) in DISCOVERY_RANGES) {
                runCatching {
                    val resp = connection.run(AvailablePIDsCommand(range))
                    add(resp.rawResponse.processedValue to base)
                }
            }
        }
        return SupportedPids.merge(responses)
    }

    companion object {
        private const val MAX_RETRIES = 1
        private val DISCOVERY_RANGES = listOf(
            AvailablePIDsCommand.AvailablePIDsRanges.PIDS_01_TO_20 to 0x00,
            AvailablePIDsCommand.AvailablePIDsRanges.PIDS_21_TO_40 to 0x20,
            AvailablePIDsCommand.AvailablePIDsRanges.PIDS_41_TO_60 to 0x40,
        )
    }
}

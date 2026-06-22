package me.crsib.freecartracker.telemetry.obd.pid

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import me.crsib.freecartracker.data.entity.Vehicle

/**
 * Stable metric keys for manufacturer (Mode 22) metrics. These match the keys the live
 * dashboard looks up in [me.crsib.freecartracker.telemetry.service.LiveTelemetry.extended]
 * and are stored as `obd_extended_samples.metricKey`.
 */
object ExtendedKeys {
    const val EV_SOC = "EV_SOC"
    const val MOTOR_TEMP = "MOTOR_TEMP"
}

/**
 * Base for a UDS Mode 22 (service 0x22) PID. Unlike Mode 01, the response echo is **3 bytes**
 * (`62` + the two PID bytes), so the data payload starts at index 3.
 *
 * @param pidHex four hex digits, e.g. "2002".
 * @param decode receives the data bytes (echo already stripped) and returns the value.
 */
abstract class Mode22Command(
    private val tagName: String,
    private val displayName: String,
    pidHex: String,
    override val defaultUnit: String,
    private val decode: (IntArray) -> Double,
) : ObdCommand() {
    override val tag = tagName
    override val name = displayName
    override val mode = "22"
    override val pid = pidHex

    override val handler: (ObdRawResponse) -> String = { response ->
        val buf = response.bufferedValue
        // Truncated / NO DATA response (no data byte after the echo) → emit a non-numeric sentinel
        // so the reading is skipped rather than fabricating a 0 % / -40 °C value.
        if (buf.size > ECHO_BYTES) decode(buf.copyOfRange(ECHO_BYTES, buf.size)).toString() else ""
    }

    private companion object {
        const val ECHO_BYTES = 3 // 0x62 + 2 PID bytes
    }
}

/** Build an [PidDefinition] for an extended (tall-table) Mode 22 metric. */
private fun mode22(
    key: String,
    name: String,
    pidHex: String,
    unit: String,
    group: MetricGroup,
    decode: (IntArray) -> Double,
): PidDefinition = PidDefinition(
    key = key,
    command = object : Mode22Command(key, name, pidHex, unit, decode) {},
    unit = unit,
    group = group,
    priority = PollPriority.SLOW,
    storage = Storage.Extended,
)

/** First data byte as a percentage (A * 100 / 255). */
private val firstByteAsPercent: (IntArray) -> Double = { d -> if (d.isNotEmpty()) d[0] * 100.0 / 255.0 else 0.0 }

/** First data byte as a temperature in °C (A - 40), the standard OBD temperature offset. */
private val firstByteAsTempC: (IntArray) -> Double = { d -> if (d.isNotEmpty()) d[0] - 40.0 else 0.0 }

/**
 * IMPORTANT: the PID numbers and scalings below are **community-sourced templates**, not
 * guaranteed for any specific model/year. Real EV PIDs vary per make/model/year and many are
 * multi-frame (needing ISO-TP reassembly beyond a single response). Treat each profile as a
 * starting point to verify against the actual vehicle, and adjust the pid/decode accordingly.
 * The framework — Mode 22 decoding, profile selection, extended-table storage, EV tiles — is
 * what is exercised by the tests.
 */
private fun evProfile(
    profileKey: String,
    makeMatches: List<String>,
    socPid: String,
    motorTempPid: String,
): PidProfile = object : PidProfile {
    override val key = profileKey

    override fun appliesTo(vehicle: Vehicle): Boolean {
        if (vehicle.powertrain.usesCharging.not()) return false // EV/REV only
        val make = vehicle.make?.lowercase() ?: return false
        return makeMatches.any { make.contains(it) }
    }

    override val pids: List<PidDefinition> = listOf(
        mode22(ExtendedKeys.EV_SOC, "EV state of charge", socPid, "%", MetricGroup.EV_HYBRID, firstByteAsPercent),
        mode22(ExtendedKeys.MOTOR_TEMP, "Electric motor temp", motorTempPid, "°C", MetricGroup.EV_HYBRID, firstByteAsTempC),
    )
}

/** Built-in manufacturer profiles. Add more makes here over time. */
object ManufacturerProfiles {
    val all: List<PidProfile> = listOf(
        // Hyundai/Kia E-GMP and earlier EVs (BMS on ECU 7E4). Verify per model/year.
        evProfile("HYUNDAI_KIA", listOf("hyundai", "kia", "genesis"), socPid = "0105", motorTempPid = "0101"),
        // Nissan Leaf — note: the Leaf's real SoC is Mode 21 multi-frame on the LBC; template only.
        evProfile("NISSAN", listOf("nissan"), socPid = "0001", motorTempPid = "0002"),
        // VW Group MEB platform (ID.3/ID.4, Audi/Škoda/Cupra siblings). Verify per model/year.
        evProfile("VAG_MEB", listOf("volkswagen", "vw", "audi", "skoda", "škoda", "cupra", "seat"), socPid = "028C", motorTempPid = "0290"),
    )
}

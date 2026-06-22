package me.crsib.freecartracker.data.sync

import me.crsib.freecartracker.data.backup.csvEscape
import me.crsib.freecartracker.data.backup.num
import me.crsib.freecartracker.telemetry.data.entity.ObdSample

/**
 * One standard [ObdSample] column. The `get`/`set` pair is the single source of truth shared
 * by [ObdCsvExporter] and the importer, so the CSV header and field mapping cannot drift.
 */
internal class ObdStdCol(
    val name: String,
    val get: (ObdSample) -> Double?,
    val set: (ObdSample, Double?) -> ObdSample,
)

/** Ordered standard OBD columns (everything on [ObdSample] except id/tripId/timestampMillis). */
internal object ObdCsvSchema {
    const val TIMESTAMP = "timestampMillis"

    val standard: List<ObdStdCol> = listOf(
        ObdStdCol("speedKmh", { it.speedKmh }, { s, v -> s.copy(speedKmh = v) }),
        ObdStdCol("rpm", { it.rpm }, { s, v -> s.copy(rpm = v) }),
        ObdStdCol("throttlePct", { it.throttlePct }, { s, v -> s.copy(throttlePct = v) }),
        ObdStdCol("engineLoadPct", { it.engineLoadPct }, { s, v -> s.copy(engineLoadPct = v) }),
        ObdStdCol("runTimeSec", { it.runTimeSec?.toDouble() }, { s, v -> s.copy(runTimeSec = v?.toInt()) }),
        ObdStdCol("coolantTempC", { it.coolantTempC }, { s, v -> s.copy(coolantTempC = v) }),
        ObdStdCol("intakeAirTempC", { it.intakeAirTempC }, { s, v -> s.copy(intakeAirTempC = v) }),
        ObdStdCol("ambientAirTempC", { it.ambientAirTempC }, { s, v -> s.copy(ambientAirTempC = v) }),
        ObdStdCol("mafGramsPerSec", { it.mafGramsPerSec }, { s, v -> s.copy(mafGramsPerSec = v) }),
        ObdStdCol("manifoldPressureKpa", { it.manifoldPressureKpa }, { s, v -> s.copy(manifoldPressureKpa = v) }),
        ObdStdCol("controlModuleVoltage", { it.controlModuleVoltage }, { s, v -> s.copy(controlModuleVoltage = v) }),
        ObdStdCol("fuelLevelPct", { it.fuelLevelPct }, { s, v -> s.copy(fuelLevelPct = v) }),
        ObdStdCol("fuelRateLph", { it.fuelRateLph }, { s, v -> s.copy(fuelRateLph = v) }),
        ObdStdCol("derivedL100", { it.derivedL100 }, { s, v -> s.copy(derivedL100 = v) }),
        ObdStdCol("derivedCo2Gkm", { it.derivedCo2Gkm }, { s, v -> s.copy(derivedCo2Gkm = v) }),
        ObdStdCol("hybridBatteryLifePct", { it.hybridBatteryLifePct }, { s, v -> s.copy(hybridBatteryLifePct = v) }),
    )

    val byName: Map<String, ObdStdCol> = standard.associateBy { it.name }

    /** Extended pivoted column header: `metricKey[unit]`, e.g. `EV_SOC[PCT]`. */
    fun extHeader(metricKey: String, unit: String): String = "$metricKey[$unit]"

    /** Parse a `metricKey[unit]` header back into (key, unit), or null if it isn't one. */
    fun parseExtHeader(header: String): Pair<String, String>? {
        val open = header.lastIndexOf('[')
        if (open <= 0 || !header.endsWith(']')) return null
        return header.substring(0, open) to header.substring(open + 1, header.length - 1)
    }
}

/**
 * Exports a trip's OBD time-series to a single CSV. The header defines the schema (so adding
 * metrics later stays backward-compatible); standard [ObdSample] columns come first, then one
 * pivoted column per distinct extended `metricKey` with its unit in the header.
 *
 * Rows are keyed by `timestampMillis` — the union of OBD and extended sample timestamps, one row
 * each. A blank cell means null (not zero), so the importer restores nulls faithfully. OBD and
 * extended samples share the same poll-loop timestamps, so they align onto the same rows.
 */
object ObdCsvExporter {
    fun export(
        samples: List<me.crsib.freecartracker.telemetry.data.entity.ObdSample>,
        extended: List<me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample>,
    ): ByteArray {
        val metricKeys = extended.map { it.metricKey }.distinct().sorted()
        val unitFor = extended.associate { it.metricKey to it.unit }

        val sb = StringBuilder()
        val header = buildList {
            add(ObdCsvSchema.TIMESTAMP)
            ObdCsvSchema.standard.forEach { add(it.name) }
            metricKeys.forEach { add(ObdCsvSchema.extHeader(it, unitFor[it] ?: "")) }
        }
        sb.append(header.joinToString(",") { csvEscape(it) }).append('\n')

        val sampleByTs = samples.associateBy { it.timestampMillis }
        val extByTs = extended.groupBy { it.timestampMillis }
            .mapValues { (_, list) -> list.associate { it.metricKey to it.value } }
        val timestamps = (samples.map { it.timestampMillis } + extended.map { it.timestampMillis })
            .distinct().sorted()

        for (ts in timestamps) {
            val s = sampleByTs[ts]
            val ext = extByTs[ts].orEmpty()
            val cells = buildList {
                add(ts.toString())
                ObdCsvSchema.standard.forEach { col -> add(s?.let { col.get(it) }?.let(::num) ?: "") }
                metricKeys.forEach { k -> add(ext[k]?.let(::num) ?: "") }
            }
            sb.append(cells.joinToString(",") { csvEscape(it) }).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

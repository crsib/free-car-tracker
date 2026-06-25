package me.crsib.freecartracker.telemetry.obd.scan

/**
 * A standard mode-`01` OBD-II PID polled alongside DIDs in watch mode, to give the correlation a
 * known reference signal (speed, RPM…). These are the legislated J1979 PIDs, so they answer on any
 * compliant car — letting you line a candidate DID's payload up against, e.g., vehicle speed.
 *
 * @param pid two hex digits, e.g. "0D" for speed (request is `01 0D`).
 * @param decode receives the data bytes after the `41 <pid>` echo and returns the engineering value.
 */
data class RefPid(
    val key: String,
    val label: String,
    val pid: String,
    val unit: String,
    val decode: (IntArray) -> Double,
)

object ReferencePids {
    val SPEED = RefPid("speed", "Speed", "0D", "km/h") { if (it.isNotEmpty()) it[0].toDouble() else 0.0 }
    val RPM = RefPid("rpm", "RPM", "0C", "rpm") { if (it.size >= 2) ((it[0] * 256) + it[1]) / 4.0 else 0.0 }
    val COOLANT = RefPid("coolant", "Coolant", "05", "°C") { if (it.isNotEmpty()) it[0] - 40.0 else 0.0 }
    val THROTTLE = RefPid("throttle", "Throttle", "11", "%") { if (it.isNotEmpty()) it[0] * 100.0 / 255.0 else 0.0 }

    /** Always logged — the most useful correlation references. */
    val core = listOf(SPEED, RPM)

    /** Added when the user opts in (a little extra bus traffic per round). */
    val optional = listOf(COOLANT, THROTTLE)
}

/** Decodes a standard mode-`01` reply for [RefPid], or null when the PID is unavailable/no-data. */
object ReferencePidReader {
    private val ERROR_MARKERS = listOf("NODATA", "STOPPED", "UNABLETOCONNECT", "ERROR", "?")

    fun read(refPid: RefPid, raw: String): Double? {
        val collapsed = raw.uppercase().replace(Regex("\\s"), "")
        if (collapsed.isEmpty() || ERROR_MARKERS.any { it in collapsed }) return null

        val uds = UdsFrames.decode(raw)?.bytes ?: return null
        val pidByte = refPid.pid.toInt(16)
        // Positive mode-01 reply: 41 <pid> <data…>.
        if (uds.size >= 3 && uds[0] == 0x41 && uds[1] == pidByte) {
            return refPid.decode(uds.drop(2).toIntArray())
        }
        return null
    }
}

package me.crsib.freecartracker.telemetry.obd.pid

import me.crsib.freecartracker.data.entity.Vehicle

/** The PID set chosen for a vehicle: the standard baseline plus any manufacturer additions. */
data class SelectedProfile(
    val key: String,
    val pids: List<PidDefinition>,
)

/**
 * Resolves which PIDs to poll for a vehicle. Always includes [StandardPidProfile]; manufacturer
 * profiles (added in Phase 6) layer Mode 22 EV/motor metrics on top when their make matches.
 */
object PidProfileRegistry {
    /** Manufacturer-specific profiles, checked in order. */
    private val manufacturerProfiles: List<PidProfile> = ManufacturerProfiles.all

    fun select(vehicle: Vehicle): SelectedProfile {
        val mfg = manufacturerProfiles.firstOrNull { it.appliesTo(vehicle) }
        return SelectedProfile(
            key = mfg?.key ?: StandardPidProfile.key,
            pids = StandardPidProfile.pids + (mfg?.pids ?: emptyList()),
        )
    }
}

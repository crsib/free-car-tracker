package me.crsib.freecartracker.telemetry.obd

import me.crsib.freecartracker.telemetry.obd.pid.Storage

/** A single decoded metric value from one PID query, in canonical units. */
data class ObdReading(
    val key: String,
    val value: Double,
    val unit: String,
    val storage: Storage,
)

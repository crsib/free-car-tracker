package me.crsib.freecartracker.telemetry.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import me.crsib.freecartracker.telemetry.obd.pid.PidDefinition
import me.crsib.freecartracker.telemetry.obd.pid.PollPriority
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Thrown when the adapter stops answering entirely (every PID disabled, or a sustained run of
 * empty cycles). Subclasses [IOException] so [TripRecorder]'s reconnect loop treats it as a
 * transient transport failure and backs off rather than ending the trip.
 */
class ObdConnectionLostException(message: String) : IOException(message)

/**
 * Decides which PIDs to query each cycle. FAST PIDs are read every cycle; SLOW PIDs are
 * round-robined a few per cycle so the FAST loop stays responsive on a low-throughput ELM327.
 * PIDs that fail repeatedly are disabled. Pure (no IO) so it is unit-testable.
 */
class AdaptivePollSchedule(
    fast: List<PidDefinition>,
    slow: List<PidDefinition>,
    private val slowPerCycle: Int = 2,
    private val maxFailures: Int = 3,
) {
    private val fast = fast.toMutableList()
    private val slow = slow.toMutableList()
    private val failures = mutableMapOf<String, Int>()
    private val _disabled = mutableSetOf<String>()
    private var slowCursor = 0

    val disabledKeys: Set<String> get() = _disabled.toSet()

    fun nextBatch(): List<PidDefinition> {
        val batch = ArrayList<PidDefinition>(fast.size + slowPerCycle)
        batch.addAll(fast)
        if (slow.isNotEmpty()) {
            repeat(slowPerCycle.coerceAtMost(slow.size)) {
                batch += slow[slowCursor % slow.size]
                slowCursor++
            }
        }
        return batch
    }

    fun recordSuccess(key: String) {
        failures.remove(key)
    }

    fun recordFailure(key: String) {
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        if (count >= maxFailures) disable(key)
    }

    private fun disable(key: String) {
        _disabled += key
        fast.removeAll { it.key == key }
        slow.removeAll { it.key == key }
    }

    companion object {
        fun from(
            defs: List<PidDefinition>,
            slowPerCycle: Int = 2,
            maxFailures: Int = 3,
        ): AdaptivePollSchedule = AdaptivePollSchedule(
            fast = defs.filter { it.priority == PollPriority.FAST },
            slow = defs.filter { it.priority == PollPriority.SLOW },
            slowPerCycle = slowPerCycle,
            maxFailures = maxFailures,
        )
    }
}

/**
 * Continuously polls an [ObdSession] according to a [AdaptivePollSchedule], emitting the
 * decoded readings of each cycle. Readings that come back null mark a failure on the schedule.
 */
class AdaptivePoller(
    private val session: ObdSession,
    private val schedule: AdaptivePollSchedule,
    private val cycleDelayMs: Long = 100,
    private val maxDeadCycles: Int = 5,
) {
    fun readings(): Flow<List<ObdReading>> = flow {
        var deadCycles = 0
        while (coroutineContext.isActive) {
            val batch = schedule.nextBatch()
            // Every PID has been disabled — the adapter/ECU has stopped responding entirely.
            if (batch.isEmpty()) {
                throw ObdConnectionLostException("all PIDs disabled after repeated failures")
            }
            val out = ArrayList<ObdReading>(batch.size)
            for (def in batch) {
                val reading = session.query(def)
                if (reading == null) {
                    schedule.recordFailure(def.key)
                } else {
                    schedule.recordSuccess(def.key)
                    out += reading
                }
            }
            // A run of fully-empty cycles means the transport dropped (every query throws and is
            // mapped to null). Surface it so the caller can reconnect instead of spinning forever.
            if (out.isEmpty()) {
                if (++deadCycles >= maxDeadCycles) {
                    throw ObdConnectionLostException("no readings for $deadCycles cycles")
                }
            } else {
                deadCycles = 0
            }
            emit(out)
            delay(cycleDelayMs)
        }
    }
}

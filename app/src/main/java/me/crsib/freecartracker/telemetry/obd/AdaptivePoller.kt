package me.crsib.freecartracker.telemetry.obd

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import me.crsib.freecartracker.telemetry.obd.pid.PidDefinition
import me.crsib.freecartracker.telemetry.obd.pid.PollPriority
import java.io.IOException
import kotlin.coroutines.coroutineContext

private const val TAG = "ObdPoll"

/**
 * Thrown when the adapter stops answering entirely (every PID disabled, or a sustained run of
 * empty cycles). Subclasses [IOException] so [TripRecorder]'s reconnect loop treats it as a
 * transient transport failure and backs off rather than ending the trip.
 */
class ObdConnectionLostException(message: String) : IOException(message)

/**
 * Decides which PIDs to query each cycle. FAST PIDs are read every cycle; SLOW PIDs are
 * round-robined a few per cycle so the FAST loop stays responsive on a low-throughput ELM327.
 * PIDs that fail repeatedly are disabled, but are re-probed every [reprobeEveryCycles] cycles so a
 * transient glitch (or a recovered connection) doesn't permanently kill a metric — otherwise a
 * degraded link can leave the session limping along on whichever PID happened to survive. Pure
 * (no IO) so it is unit-testable.
 */
class AdaptivePollSchedule(
    fast: List<PidDefinition>,
    slow: List<PidDefinition>,
    private val slowPerCycle: Int = 2,
    private val maxFailures: Int = 3,
    private val reprobeEveryCycles: Int = 120,
) {
    private val fast = fast.toMutableList()
    private val slow = slow.toMutableList()
    private val failures = mutableMapOf<String, Int>()
    private val _disabled = mutableSetOf<String>()
    private val disabledDefs = mutableListOf<PidDefinition>()
    private var slowCursor = 0
    private var cycle = 0

    val disabledKeys: Set<String> get() = _disabled.toSet()

    /** Keys still being polled (FAST + SLOW), for diagnostics. */
    val activeKeys: Set<String> get() = (fast.asSequence() + slow.asSequence()).map { it.key }.toSet()

    fun nextBatch(): List<PidDefinition> {
        cycle++
        if (reprobeEveryCycles > 0 && disabledDefs.isNotEmpty() && cycle % reprobeEveryCycles == 0) {
            reprobeDisabled()
        }
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
        val def = fast.firstOrNull { it.key == key } ?: slow.firstOrNull { it.key == key }
        if (def != null) disabledDefs += def
        fast.removeAll { it.key == key }
        slow.removeAll { it.key == key }
    }

    /** Give every disabled PID another chance: clear its failures and put it back in its tier. */
    private fun reprobeDisabled() {
        disabledDefs.forEach { def ->
            failures.remove(def.key)
            _disabled -= def.key
            if (def.priority == PollPriority.FAST) fast += def else slow += def
        }
        disabledDefs.clear()
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
        var cycle = 0
        var prevDisabled = emptySet<String>()
        val producedSinceHealth = HashSet<String>()
        while (coroutineContext.isActive) {
            cycle++
            val batch = schedule.nextBatch()
            // Every PID has been disabled — the adapter/ECU has stopped responding entirely.
            if (batch.isEmpty()) {
                Log.w(TAG, "all PIDs disabled after repeated failures — treating as connection lost")
                throw ObdConnectionLostException("all PIDs disabled after repeated failures")
            }
            val out = ArrayList<ObdReading>(batch.size)
            for (def in batch) {
                val readings = session.query(def)
                if (readings.isEmpty()) {
                    schedule.recordFailure(def.key)
                } else {
                    schedule.recordSuccess(def.key)
                    out += readings
                    readings.forEach { producedSinceHealth += it.key }
                }
            }

            // Log when a metric drops out (disabled) or comes back (re-probed) — the trail for a
            // session that ends up stuck on one PID.
            val nowDisabled = schedule.disabledKeys
            (nowDisabled - prevDisabled).takeIf { it.isNotEmpty() }?.let {
                Log.w(TAG, "PID(s) disabled after repeated failures: ${it.sorted()}; still active: ${schedule.activeKeys.sorted()}")
            }
            (prevDisabled - nowDisabled).takeIf { it.isNotEmpty() }?.let {
                Log.i(TAG, "re-probing previously disabled PID(s): ${it.sorted()}")
            }
            prevDisabled = nowDisabled

            // Periodic health snapshot: which PIDs actually produced data, and what's disabled.
            if (cycle % HEALTH_EVERY_CYCLES == 0) {
                Log.i(
                    TAG,
                    "health cycle=$cycle: produced=${producedSinceHealth.sorted()}; " +
                        "active=${schedule.activeKeys.size}; disabled=${nowDisabled.sorted()}; deadCycles=$deadCycles",
                )
                producedSinceHealth.clear()
            }

            // A run of fully-empty cycles means the transport dropped (every query throws and is
            // mapped to null). Surface it so the caller can reconnect instead of spinning forever.
            if (out.isEmpty()) {
                if (++deadCycles >= maxDeadCycles) {
                    Log.w(TAG, "no readings for $deadCycles cycles — treating as connection lost")
                    throw ObdConnectionLostException("no readings for $deadCycles cycles")
                }
            } else {
                deadCycles = 0
            }
            emit(out)
            delay(cycleDelayMs)
        }
    }

    private companion object {
        const val HEALTH_EVERY_CYCLES = 100
    }
}

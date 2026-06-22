package me.crsib.freecartracker.telemetry.obd

import com.github.eltonvs.obd.command.ObdCommand
import me.crsib.freecartracker.telemetry.obd.pid.MetricGroup
import me.crsib.freecartracker.telemetry.obd.pid.PidDefinition
import me.crsib.freecartracker.telemetry.obd.pid.PollPriority
import me.crsib.freecartracker.telemetry.obd.pid.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePollScheduleTest {

    private fun def(key: String, priority: PollPriority) = PidDefinition(
        key = key,
        command = object : ObdCommand() {
            override val tag = key
            override val name = key
            override val mode = "01"
            override val pid = "00"
        },
        unit = "",
        group = MetricGroup.CORE,
        priority = priority,
        storage = Storage.Wide(key),
    )

    @Test
    fun everyBatchHasAllFastPids() {
        val schedule = AdaptivePollSchedule(
            fast = listOf(def("a", PollPriority.FAST), def("b", PollPriority.FAST)),
            slow = listOf(def("s1", PollPriority.SLOW), def("s2", PollPriority.SLOW), def("s3", PollPriority.SLOW)),
            slowPerCycle = 1,
        )
        repeat(3) {
            val keys = schedule.nextBatch().map { it.key }
            assertTrue("a" in keys && "b" in keys)
        }
    }

    @Test
    fun slowPidsRoundRobin() {
        val schedule = AdaptivePollSchedule(
            fast = emptyList(),
            slow = listOf(def("s1", PollPriority.SLOW), def("s2", PollPriority.SLOW), def("s3", PollPriority.SLOW)),
            slowPerCycle = 1,
        )
        assertEquals(listOf("s1"), schedule.nextBatch().map { it.key })
        assertEquals(listOf("s2"), schedule.nextBatch().map { it.key })
        assertEquals(listOf("s3"), schedule.nextBatch().map { it.key })
        assertEquals(listOf("s1"), schedule.nextBatch().map { it.key })
    }

    @Test
    fun failingPidGetsDisabledAfterMaxFailures() {
        val schedule = AdaptivePollSchedule(
            fast = listOf(def("a", PollPriority.FAST), def("b", PollPriority.FAST)),
            slow = emptyList(),
            maxFailures = 3,
        )
        repeat(3) { schedule.recordFailure("b") }
        assertTrue("b" in schedule.disabledKeys)
        val keys = schedule.nextBatch().map { it.key }
        assertTrue("a" in keys)
        assertFalse("b" in keys)
    }

    @Test
    fun successResetsFailureCount() {
        val schedule = AdaptivePollSchedule(
            fast = listOf(def("a", PollPriority.FAST)),
            slow = emptyList(),
            maxFailures = 3,
        )
        schedule.recordFailure("a")
        schedule.recordFailure("a")
        schedule.recordSuccess("a") // reset
        schedule.recordFailure("a")
        schedule.recordFailure("a")
        assertFalse("a" in schedule.disabledKeys) // only 2 consecutive since reset
    }
}

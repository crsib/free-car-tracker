package me.crsib.freecartracker.telemetry.obd.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntRangeSetTest {

    @Test
    fun coalescesAdjacentAndOverlappingRuns() {
        val s = IntRangeSet()
        s.add(0, 5)
        s.add(6, 10) // adjacent to [0,5] -> should merge into [0,10]
        s.add(8, 20) // overlaps -> [0,20]
        s.add(100, 100)
        assertEquals(listOf(0 to 20, 100 to 100), s.pairs())
        assertEquals(22, s.count)
    }

    @Test
    fun singlePointAddsCoalesceIntoOneRange() {
        val s = IntRangeSet()
        for (d in 0x2000..0x200F) s.add(d, d) // ascending single points, like a dead run
        assertEquals(listOf(0x2000 to 0x200F), s.pairs())
    }

    @Test
    fun containsRespectsBoundaries() {
        val s = IntRangeSet.fromPairs(listOf(10 to 20, 30 to 30))
        assertTrue(s.contains(10)); assertTrue(s.contains(20)); assertTrue(s.contains(15))
        assertTrue(s.contains(30))
        assertFalse(s.contains(9)); assertFalse(s.contains(21)); assertFalse(s.contains(29))
    }

    @Test
    fun mergingKeepsRangesSortedRegardlessOfInsertOrder() {
        val s = IntRangeSet()
        s.add(50, 60)
        s.add(0, 10)
        s.add(20, 30)
        assertEquals(listOf(0 to 10, 20 to 30, 50 to 60), s.pairs())
    }

    @Test
    fun fromPairsRoundTrips() {
        val pairs = listOf(0 to 0, 5 to 9, 0xFFF0 to 0xFFFF)
        assertEquals(pairs, IntRangeSet.fromPairs(pairs).pairs())
    }
}

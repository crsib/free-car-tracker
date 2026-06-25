package me.crsib.freecartracker.telemetry.obd.scan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DidScanMemoryStoreTest {

    private lateinit var file: File
    private lateinit var store: DidScanMemoryStore
    private val ecu = EcuTarget("BMS?", "7E4")
    private val parked = ScanContext(ScanStates.PARKED, ScanContext.SESSION_DEFAULT)
    private val driving = ScanContext(ScanStates.DRIVING, ScanContext.SESSION_DEFAULT)

    @Before
    fun setUp() {
        file = File.createTempFile("did-mem", ".json").apply { delete() }
        store = DidScanMemoryStore(file)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun classification(dead: List<Pair<Int, Int>>, retryable: Map<Int, Int>, hits: List<Int>) =
        listOf(EcuClassification(ecu, hits, dead, retryable))

    @Test
    fun mergeThenSkip_skipsDeadButNotHitsOrRetryable() {
        val doc = store.merge(
            store.load(), parked,
            classification(dead = listOf(0x0000 to 0x1FFF), retryable = mapOf(0x2002 to 0x22), hits = listOf(0x2003)),
        )
        val skip = store.skipPredicate(doc, parked)

        assertTrue("a 0x31 DID is skipped", skip("7E4", 0x1000))
        assertFalse("a hit is never skipped", skip("7E4", 0x2003))
        assertFalse("a retryable DID is never skipped", skip("7E4", 0x2002))
        assertFalse("a DID outside the dead range is probed", skip("7E4", 0x3000))
    }

    @Test
    fun skipIsScopedPerContext_deadParkedIsNotDeadDriving() {
        val doc = store.merge(
            store.load(), parked,
            classification(dead = listOf(0x0000 to 0x2FFF), retryable = emptyMap(), hits = emptyList()),
        )
        assertTrue(store.skipPredicate(doc, parked)("7E4", 0x1234))
        // Same DID in the DRIVING context has no memory yet — it must be re-probed.
        assertFalse(store.skipPredicate(doc, driving)("7E4", 0x1234))
    }

    @Test
    fun interestingDids_isHitsAndRetryableAcrossContexts() {
        var doc = store.merge(store.load(), parked, classification(emptyList(), mapOf(0x2002 to NRC_NO_DATA), listOf(0x2003)))
        doc = store.merge(doc, driving, classification(emptyList(), mapOf(0x4000 to 0x22), listOf(0x2003)))
        assertEquals(listOf(0x2002, 0x2003, 0x4000), store.interestingDids(doc))
    }

    @Test
    fun merge_persistsAcrossReload() {
        store.merge(store.load(), parked, classification(listOf(0x0 to 0xFF), emptyMap(), listOf(0x10)))
        val reloaded = DidScanMemoryStore(file).load()
        assertTrue(store.skipPredicate(reloaded, parked)("7E4", 0x50))
        assertEquals(listOf(0x10), store.interestingDids(reloaded))
    }

    @Test
    fun merge_laterHitSupersedesEarlierRetryable() {
        var doc = store.merge(store.load(), parked, classification(emptyList(), mapOf(0x2002 to 0x22), emptyList()))
        doc = store.merge(doc, parked, classification(emptyList(), emptyMap(), listOf(0x2002)))
        // 0x2002 was retryable, now a hit: it should be in interesting once, and not skipped.
        assertEquals(listOf(0x2002), store.interestingDids(doc))
        assertFalse(store.skipPredicate(doc, parked)("7E4", 0x2002))
    }
}

package me.crsib.freecartracker.telemetry.obd.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class DidWatchCsvTest {

    private val refs = ReferencePids.core // speed, rpm
    private val targetKeys = listOf("7E8:2002", "7E4:4000")

    @Test
    fun header_listsTimestampRefsThenDids() {
        assertEquals(
            "timestampMillis,Speed[km/h],RPM[rpm],7E8:2002,7E4:4000",
            DidWatchCsv.header(refs, targetKeys),
        )
    }

    @Test
    fun row_alignsValuesAndBlanksMissingOnes() {
        val row = DidWatchCsv.row(
            timestampMillis = 1000,
            refValues = mapOf("speed" to 80.0, "rpm" to null), // rpm unavailable this round
            didValues = mapOf("7E8:2002" to "AABB"),            // 7E4:4000 not read yet
            refPids = refs,
            targetKeys = targetKeys,
        )
        assertEquals("1000,80,,AABB,", row)
    }
}

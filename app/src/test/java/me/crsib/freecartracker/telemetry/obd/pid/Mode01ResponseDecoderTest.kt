package me.crsib.freecartracker.telemetry.obd.pid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mode01ResponseDecoderTest {

    @Test
    fun singleFrame_returnsBytesAfterEcho() {
        assertArrayEquals(
            intArrayOf(0x1A, 0xF8),
            Mode01ResponseDecoder.dataBytes("41 0C 1A F8", "0C"),
        )
    }

    @Test
    fun multiEcu_takesFirstResponder() {
        // Five ECUs answer 0142 (headers off, each on its own line). First responder wins.
        assertArrayEquals(
            intArrayOf(0x34, 0x23),
            Mode01ResponseDecoder.dataBytes("41423423\r414235F0\r41423617\r414235D5\r414235E8", "42"),
        )
    }

    @Test
    fun isoTpMultiFrame_reassemblesInOrder() {
        // 008 length line + 0:/1: continuation lines -> 41 9A 07 00 5D 73 00 19 (+ 55 padding).
        assertArrayEquals(
            intArrayOf(0x07, 0x00, 0x5D, 0x73, 0x00, 0x19, 0x55, 0x55, 0x55, 0x55, 0x55),
            Mode01ResponseDecoder.dataBytes("008\r0:419A07005D7300\r1:195555555555", "9A"),
        )
    }

    @Test
    fun noMatchingPid_returnsNull() {
        assertNull(Mode01ResponseDecoder.dataBytes("41 0C 1A F8", "9A"))
    }

    @Test
    fun empty_returnsNull() {
        assertNull(Mode01ResponseDecoder.dataBytes("", "0C"))
    }
}

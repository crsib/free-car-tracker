package me.crsib.freecartracker.telemetry.obd.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classification of raw ELM327 replies to `22 <did>` — the deterministic core of the scanner. */
class DidResponseParserTest {

    @Test
    fun positiveSingleFrame_headersAndSpacesOn_stripsEchoAndCapturesResponder() {
        // 7E8 = responder; 06 = SF len 6; 62 F1 90 = positive echo; 12 34 56 = payload.
        val r = DidResponseParser.parse(0xF190, "7E8 06 62 F1 90 12 34 56")
        assertEquals(DidResponseKind.POSITIVE, r.kind)
        assertEquals("7E8", r.responderHeader)
        assertEquals("123456", r.payloadHex)
        assertFalse(r.multiFrame)
    }

    @Test
    fun positive_withNoPayloadBytes_isStillAHit() {
        // 62 20 02 with no data after the echo — DID exists but returned nothing extra.
        val r = DidResponseParser.parse(0x2002, "7E8 03 62 20 02")
        assertEquals(DidResponseKind.POSITIVE, r.kind)
        assertEquals("", r.payloadHex)
    }

    @Test
    fun negativeResponse_capturesNrc() {
        // 7F 22 31 = service 0x22 rejected, NRC 0x31 (requestOutOfRange) — the usual "no such DID".
        val r = DidResponseParser.parse(0x1234, "7E8 03 7F 22 31")
        assertEquals(DidResponseKind.NEGATIVE, r.kind)
        assertEquals(0x31, r.negativeCode)
        assertEquals("7E8", r.responderHeader)
    }

    @Test
    fun noData_classifiedAsNoData() {
        val r = DidResponseParser.parse(0x1234, "NO DATA")
        assertEquals(DidResponseKind.NO_DATA, r.kind)
        assertNull(r.responderHeader)
    }

    @Test
    fun adapterErrorsClassifiedAsError() {
        assertEquals(DidResponseKind.ERROR, DidResponseParser.parse(0x1234, "?").kind)
        assertEquals(DidResponseKind.ERROR, DidResponseParser.parse(0x1234, "CAN ERROR").kind)
        assertEquals(DidResponseKind.ERROR, DidResponseParser.parse(0x1234, "BUS INIT: ...ERROR").kind)
    }

    @Test
    fun emptyReply_classifiedAsEmpty() {
        assertEquals(DidResponseKind.EMPTY, DidResponseParser.parse(0x1234, "   ").kind)
    }

    @Test
    fun multiFrame_reassemblesToDeclaredLength() {
        // First frame declares total length 0x014 = 20 bytes: 62 F1 90 + 17 payload bytes.
        val raw = buildString {
            appendLine("7E8 10 14 62 F1 90 01 02 03")   // FF: 6 bytes here (62 F1 90 01 02 03)
            appendLine("7E8 21 04 05 06 07 08 09 0A")   // CF#1: 7 bytes
            appendLine("7E8 22 0B 0C 0D 0E 0F 10 11")   // CF#2: 7 bytes (total 20, trimmed)
        }
        val r = DidResponseParser.parse(0xF190, raw)
        assertEquals(DidResponseKind.POSITIVE, r.kind)
        assertTrue(r.multiFrame)
        // 20 UDS bytes minus the 3-byte echo = 17 payload bytes = 34 hex chars.
        assertEquals(34, r.payloadHex.length)
        assertTrue(r.payloadHex.startsWith("0102030405"))
        assertTrue(r.payloadHex.endsWith("1011"))
    }

    @Test
    fun headersOff_noResponderButStillDecodes() {
        val r = DidResponseParser.parse(0xF190, "06 62 F1 90 AB CD EF")
        assertEquals(DidResponseKind.POSITIVE, r.kind)
        assertNull(r.responderHeader)
        assertEquals("ABCDEF", r.payloadHex)
    }

    @Test
    fun noSpaces_singleRunStillDecodes() {
        // Spaces-off SF: 0662F19012 -> SF len 6, echo 62 F1 90, payload 12.
        val r = DidResponseParser.parse(0xF190, "0662F19012")
        assertEquals(DidResponseKind.POSITIVE, r.kind)
        assertEquals("12", r.payloadHex)
    }
}

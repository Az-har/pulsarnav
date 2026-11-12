package com.pulsarnav.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class OtcPacketBuilderCriticTest {

    @Test
    fun buildPlaylistFrame_clipsMultibyteUtf8WithoutBrokenSequences() {
        val longTitle = "अरिजीत सिंह — केसरिया तेरा इश्क़ है 🎵🔥🎸"
        val frame = OtcPacketBuilder.buildPlaylistFrame(
            trackTitles = listOf(longTitle, "€".repeat(20)),
            totalSongs = 2,
            totalPages = 1,
            currentTrackNumber = 1,
            pagePlaylistAck = 1
        )
        assertEquals(OtcPacketBuilder.PLAYLIST_PACKET_SIZE, frame.size)

        for (slot in 0..1) {
            val base = 6 + slot * 33
            val len = frame[base].toInt() and 0xFF
            assertTrue("UTF-8 slot $slot exceeded 32 bytes: $len", len in 1..32)
            val decoded = String(frame, base + 1, len, StandardCharsets.UTF_8)
            assertFalse("Decoded slot $slot contained broken UTF-8 U+FFFD", decoded.contains('\uFFFD'))
            assertEquals(len, decoded.toByteArray(StandardCharsets.UTF_8).size)
        }
    }

    @Test
    fun normalizeClusterDigits_preservesInternationalUpTo15AndCapsLongerNumbers() {
        assertEquals("919876543210", OtcPacketBuilder.normalizeClusterDigits("+91 98765 43210"))
        assertEquals("442079460958", OtcPacketBuilder.normalizeClusterDigits("+44 20 7946 0958"))
        assertEquals("456789012345678", OtcPacketBuilder.normalizeClusterDigits("+123456789012345678"))
        assertEquals("00919876543210", OtcPacketBuilder.normalizeClusterDigits("00919876543210"))
        assertEquals(14, OtcPacketBuilder.normalizeClusterDigits("00919876543210").length)
    }

    @Test
    fun parseControlFrame_preservesRawDialIndexAbove4And0xFF() {
        val frame = ByteArray(18)
        frame[16] = 0xFF.toByte()
        val parsedSentinel = OtcPacketBuilder.parseControlFrame(frame)
        assertNotNull(parsedSentinel)
        assertEquals(0xFF, parsedSentinel!!.dialIndex)

        frame[16] = 0x09.toByte()
        val parsedHighIndex = OtcPacketBuilder.parseControlFrame(frame)
        assertNotNull(parsedHighIndex)
        assertEquals(9, parsedHighIndex!!.dialIndex)
    }

    @Test
    fun toHexAndTransliterateAscii_handleFastPathAndAccentsDeterministically() {
        assertEquals("", OtcPacketBuilder.toHex(ByteArray(0)))
        assertEquals(
            "00 0F A5 FF",
            OtcPacketBuilder.toHex(byteArrayOf(0x00, 0x0F, 0xA5.toByte(), 0xFF.toByte()))
        )
        assertEquals("MG Road 42", OtcPacketBuilder.transliterateAscii("MG Road 42"))
        assertEquals("Cafe Creme", OtcPacketBuilder.transliterateAscii("Café Crème"))
    }
}

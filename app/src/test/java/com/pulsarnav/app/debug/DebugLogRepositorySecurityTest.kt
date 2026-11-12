package com.pulsarnav.app.debug

import com.pulsarnav.app.protocol.OtcPacketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

class DebugLogRepositorySecurityTest {

    @Before
    fun setUp() {
        DebugLogRepository.clear()
    }

    @Test
    fun summarizeBlePacket_masksNamesOn0310And0410AndCapsBleTxHexHeaders() {
        val missedCall = OtcPacketBuilder.buildMissedCallPacket("Bob Builder", "+919876543210")
        val missedSummary = DebugLogRepository.summarizeBlePacket(missedCall, "0310")
        assertFalse(missedSummary.contains("Bob"))
        assertFalse(missedSummary.contains("Builder"))
        assertFalse(missedSummary.contains("9876543210"))
        assertTrue(missedSummary.contains("B***r"))

        val alert = OtcPacketBuilder.buildWhatsAppAlertPacket("Charlie")
        val alertSummary = DebugLogRepository.summarizeBlePacket(alert, "0410")
        assertFalse(alertSummary.contains("Charlie"))
        assertTrue(alertSummary.contains("C***e"))

        DebugLogRepository.bleTx("0110", ByteArray(48) { 0x41 })
        DebugLogRepository.bleTx("0210", ByteArray(54) { 0x42 })
        DebugLogRepository.bleTx("0310", missedCall)

        val entries = DebugLogRepository.entries.value
        assertEquals(3, entries.size)
        val tbtHex = entries[0].detail
        val genHex = entries[1].detail
        val callHex = entries[2].detail

        // 0110 caps at 14 bytes, 0210 at 18 bytes, 0310 at 2 bytes -> formatted as "<bytes> ... (<N>B)"
        assertTrue("Expected truncated 0110 header hex, got: $tbtHex", tbtHex.contains(" ... (48B)"))
        assertEquals(14, tbtHex.substringBefore(" ... (").trim().split(" ").size)
        assertTrue("Expected truncated 0210 header hex, got: $genHex", genHex.contains(" ... (54B)"))
        assertEquals(18, genHex.substringBefore(" ... (").trim().split(" ").size)
        assertTrue("Expected truncated 0310 header hex, got: $callHex", callHex.contains(" ... (${missedCall.size}B)"))
        assertEquals(2, callHex.substringBefore(" ... (").trim().split(" ").size)
    }

    @Test
    fun redactSensitiveText_masksInternationalAndPunctuatedPhoneNumbersOnly() {
        val redacted = DebugLogRepository.redactSensitiveText(
            "Dial +44 20 7946 0958 or 987.654.3210 or 123456789012345 dist=120 m spd=85 km/h"
        )
        assertFalse(redacted.contains("7946"))
        assertFalse(redacted.contains("987.654.3210"))
        assertFalse(redacted.contains("123456789012345"))
        assertTrue(redacted.contains("***0958"))
        assertTrue(redacted.contains("***3210"))
        assertTrue(redacted.contains("***2345"))
        assertTrue(redacted.contains("dist=120 m spd=85 km/h"))
    }

    @Test
    fun clear_resetsTotalCountAndRateLimitMapCapsDistinctKeys() {
        for (i in 0 until 300) {
            DebugLogRepository.bleTx(
                uuidShort = "0110",
                bytes = ByteArray(48),
                rateLimitKey = "k_$i",
                minIntervalMs = 5_000L
            )
        }
        assertEquals(300L, DebugLogRepository.totalCount.value)
        assertEquals(300, DebugLogRepository.entries.value.size)

        // Immediate repeat on latest key should still be suppressed
        DebugLogRepository.bleTx(
            uuidShort = "0110",
            bytes = ByteArray(48),
            rateLimitKey = "k_299",
            minIntervalMs = 5_000L
        )
        assertEquals(300L, DebugLogRepository.totalCount.value)

        DebugLogRepository.clear()
        assertEquals(0L, DebugLogRepository.totalCount.value)
        assertEquals(0, DebugLogRepository.entries.value.size)
        assertEquals(0, DebugLogRepository.errorCount.value)
    }

    @Test
    fun concurrentLogAndClear_staysConsistentWithoutExceptions() {
        val errors = mutableListOf<Throwable>()
        val threads = (0 until 4).map { worker ->
            thread(start = true) {
                try {
                    for (i in 0 until 150) {
                        DebugLogRepository.log(
                            category = if (i % 10 == 0) DebugCategory.ERROR else DebugCategory.BLE,
                            tag = "Worker$worker",
                            summary = "msg #$i"
                        )
                        if (worker == 0 && i % 50 == 0) {
                            DebugLogRepository.clear()
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                }
            }
        }
        threads.forEach { it.join(5_000L) }

        assertTrue("Unexpected concurrency errors: $errors", errors.isEmpty())
        val entries = DebugLogRepository.entries.value
        assertTrue(entries.size <= DebugLogRepository.MAX_ENTRIES)
        assertTrue(DebugLogRepository.totalCount.value >= entries.size.toLong())
    }

    @Test
    fun cachedErrorCount_tracksRingBufferEvictionsAndToCompactHexEdgeCases() {
        DebugLogRepository.error("ERR_TAG", "initial error")
        assertEquals(1, DebugLogRepository.errorCount.value)

        // Evict initial error out of 350-entry ring buffer with non-error entries
        for (i in 0 until DebugLogRepository.MAX_ENTRIES) {
            DebugLogRepository.bleEvent("ok #$i")
        }
        assertEquals(DebugLogRepository.MAX_ENTRIES, DebugLogRepository.entries.value.size)
        assertEquals(0, DebugLogRepository.errorCount.value)

        // Fast-path (<7 digits) preserves non-phone numbers; 7+ digits mask cleanly
        assertEquals("Pin 123456 ok", DebugLogRepository.redactSensitiveText("Pin 123456 ok"))
        assertEquals("Call ***1234", DebugLogRepository.redactSensitiveText("Call 555-1234"))

        assertEquals("0B", DebugLogRepository.toCompactHex(ByteArray(0)))
        assertEquals("0A FF (2B)", DebugLogRepository.toCompactHex(byteArrayOf(0x0A, 0xFF.toByte()), maxBytes = 2))
        assertEquals("0A 1B ... (4B)", DebugLogRepository.toCompactHex(byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D), maxBytes = 2))
        assertEquals("... (3B)", DebugLogRepository.toCompactHex(byteArrayOf(1, 2, 3), maxBytes = 0))
    }
}

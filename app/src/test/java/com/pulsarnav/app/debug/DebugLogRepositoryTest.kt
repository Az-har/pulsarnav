package com.pulsarnav.app.debug

import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.protocol.ClusterContactEntry
import com.pulsarnav.app.protocol.ClusterRecentCallEntry
import com.pulsarnav.app.protocol.OtcPacketBuilder
import com.pulsarnav.app.protocol.RecentCallStatus
import com.pulsarnav.app.wifi.TftTcpFrame
import com.pulsarnav.app.wifi.TftWifiProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogRepositoryTest {

    @Before
    fun setup() {
        DebugLogRepository.clear()
    }

    @Test
    fun `ring buffer enforces MAX_ENTRIES bound and tracks errors`() {
        val totalToAdd = DebugLogRepository.MAX_ENTRIES + 40
        for (i in 1..totalToAdd) {
            val cat = if (i % 50 == 0) DebugCategory.ERROR else DebugCategory.BLE
            DebugLogRepository.log(cat, "Test", "event-$i", timestampMs = 1_000L + i)
        }
        val entries = DebugLogRepository.entries.value
        assertEquals(DebugLogRepository.MAX_ENTRIES, entries.size)
        assertTrue(entries.first().summary.endsWith("41"))
        assertTrue(entries.last().summary.endsWith(totalToAdd.toString()))
        assertEquals(entries.count { it.category == DebugCategory.ERROR }, DebugLogRepository.errorCount.value)
    }

    @Test
    fun `maskPhoneNumber and redactSensitiveText strip full numbers`() {
        assertEquals("***3210", DebugLogRepository.maskPhoneNumber("+91 98765 43210"))
        assertEquals("<empty>", DebugLogRepository.maskPhoneNumber("  "))
        assertEquals("***", DebugLogRepository.maskPhoneNumber("12"))

        val sanitized = DebugLogRepository.redactSensitiveText("Dialing +91 98765 43210 from cluster")
        assertFalse(sanitized.contains("98765"))
        assertTrue(sanitized.contains("***3210"))
    }

    @Test
    fun `filterEntries filters by category and case-insensitive search`() {
        DebugLogRepository.bleEvent("Connected PULSAR400")
        DebugLogRepository.wifi("TCP", "NAV_V2 0x09 OK")
        DebugLogRepository.navV2("NAV_V2", "Loaded 3 favs")
        DebugLogRepository.telephony("SMS", "Sent reject SMS")
        DebugLogRepository.error("GATT", "Timeout error")

        assertEquals(1, DebugLogRepository.filterEntries(DebugLogRepository.entries.value, DebugFilter.BLE).size)
        assertEquals(2, DebugLogRepository.filterEntries(DebugLogRepository.entries.value, DebugFilter.WIFI_NAV_V2).size)
        assertEquals(1, DebugLogRepository.filterEntries(DebugLogRepository.entries.value, DebugFilter.ERRORS).size)
        assertEquals(
            1,
            DebugLogRepository.filterEntries(DebugLogRepository.entries.value, DebugFilter.ALL, "favs").size
        )
    }

    @Test
    fun `rateLimited suppresses duplicate spam inside cooldown`() {
        DebugLogRepository.logRateLimited("tbt_key", 5_000L, DebugCategory.BLE, "TX", "step 1", timestampMs = 10_000L)
        DebugLogRepository.logRateLimited("tbt_key", 5_000L, DebugCategory.BLE, "TX", "step 2", timestampMs = 12_000L)
        DebugLogRepository.logRateLimited("tbt_key", 5_000L, DebugCategory.BLE, "TX", "step 3", timestampMs = 15_500L)

        val list = DebugLogRepository.entries.value
        assertEquals(2, list.size)
        assertEquals("step 1", list[0].summary)
        assertEquals("step 3", list[1].summary)
    }

    @Test
    fun `summarizeBlePacket decodes TBT general media phonebook and control frames`() {
        val tbt = OtcPacketBuilder.buildTbtFrame(
            type = 1,
            maneuverIcon = Maneuver.TURN_LEFT.opcode.toInt(),
            stepMeters = 80,
            totalMeters = 2400,
            streetText = "MG Road"
        )
        val tbtSummary = DebugLogRepository.summarizeBlePacket(tbt)
        assertTrue(tbtSummary.contains("0110"))
        assertTrue(tbtSummary.contains("TURN_LEFT*"))
        assertTrue(tbtSummary.contains("80m"))
        assertTrue(tbtSummary.contains("MG Road"))

        val gen = OtcPacketBuilder.buildGeneralPacketV2(
            batteryPercent = 82,
            volumeLevel = 7,
            signalBars = 4,
            callState = CallState.INCOMING_CALL,
            callerName = "Alice",
            missedCallCount = 2
        )
        val genSummary = DebugLogRepository.summarizeBlePacket(gen)
        assertTrue(genSummary.contains("INCOMING_CALL"))
        assertFalse(genSummary.contains("Alice"))
        assertTrue(genSummary.contains("A***e"))
        assertTrue(genSummary.contains("missed=2"))

        val media = OtcPacketBuilder.buildMusicInfoFrame("Believer", "Imagine Dragons", playStatus = 2)
        val mediaSummary = DebugLogRepository.summarizeBlePacket(media)
        assertTrue(mediaSummary.contains("PLAY"))
        assertTrue(mediaSummary.contains("Believer"))

        val fav = OtcPacketBuilder.buildFavContactsFrame(listOf(ClusterContactEntry("Mom", "9876543210")))
        assertTrue(DebugLogRepository.summarizeBlePacket(fav).contains("0910 FavContacts slots=1/5"))

        val recents = OtcPacketBuilder.buildRecentCallsFrame(
            listOf(ClusterRecentCallEntry("Dad", "9123456789", RecentCallStatus.MISSED))
        )
        assertTrue(DebugLogRepository.summarizeBlePacket(recents).contains("0b10 RecentCalls slots=1/5"))
    }

    @Test
    fun `summarizeTcpFrame names NAV_V2 and screen commands cleanly`() {
        val frame = TftTcpFrame(
            type = TftWifiProtocol.TYPE_CLUSTER_REQUEST,
            group = TftWifiProtocol.GROUP_DATA,
            cmd = TftWifiProtocol.CMD_NAV_V2_GET_FAVOURITES,
            payload = byteArrayOf()
        )
        val summary = DebugLogRepository.summarizeTcpFrame("RX", frame)
        assertTrue(summary.contains("NAV_V2_GET_FAVOURITES"))
        assertTrue(summary.contains("0B"))
    }

    @Test
    fun `formatLogsForExport includes header and formatted lines`() {
        DebugLogRepository.wifi("TCP", "Hello TFT")
        val exported = DebugLogRepository.formatLogsForExport(
            DebugLogRepository.entries.value,
            headerInfo = "Device: Pixel • Model: ns400z"
        )
        assertTrue(exported.contains("PulsarNav Diagnostic Log"))
        assertTrue(exported.contains("Model: ns400z"))
        assertTrue(exported.contains("[WI-FI] TCP: Hello TFT"))
    }
}

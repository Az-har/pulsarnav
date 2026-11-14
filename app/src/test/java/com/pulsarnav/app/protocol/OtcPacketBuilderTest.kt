package com.pulsarnav.app.protocol

import com.pulsarnav.app.data.VehicleCatalog
import com.pulsarnav.app.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class OtcPacketBuilderTest {

    @Test
    fun buildTbtStopFrame_isAllZerosWithZeroChecksum() {
        val frame = OtcPacketBuilder.buildTbtStopFrame()
        assertEquals(OtcPacketBuilder.TBT_PACKET_SIZE, frame.size)
        for (i in frame.indices) {
            assertEquals("Expected zero byte at index $i", 0, frame[i].toInt())
        }
    }

    @Test
    fun buildTbtFrame_typeZeroDoesNotBlinkOrForceNonZeroEta() {
        val frame = OtcPacketBuilder.buildTbtFrame(
            type = 0,
            maneuverIcon = 0,
            isAm = true,
            stepMeters = 0,
            totalMeters = 0,
            etaHour = 0,
            etaMinute = 0,
            roundaboutExit = 0,
            streetText = ""
        )
        assertEquals(OtcPacketBuilder.TBT_PACKET_SIZE, frame.size)
        assertEquals(0, frame[1].toInt())
        assertEquals(0, frame[7].toInt())
        assertEquals(0, frame[12].toInt() and 0x0C) // gpsActive = 0
        assertChecksumValid(frame, 47)
    }

    @Test
    fun buildTbtFrame_blinksUnder100mExceptDestination() {
        val normalTurn = OtcPacketBuilder.buildTbtFrame(
            type = 1,
            maneuverIcon = Maneuver.TURN_LEFT.opcode.toInt(),
            stepMeters = 80,
            totalMeters = 1200,
            etaHour = 9,
            etaMinute = 30,
            isAm = true,
            streetText = "MG Road"
        )
        assertEquals((Maneuver.TURN_LEFT.opcode.toInt() + 32).toByte(), normalTurn[1])
        assertEquals(1, normalTurn[0].toInt() and 0x01)
        assertEquals(0x10, normalTurn[0].toInt() and 0x10) // step in meters
        assertEquals(0x04, normalTurn[12].toInt() and 0x0C) // gpsActive = 1
        assertChecksumValid(normalTurn, 47)

        val arrived = OtcPacketBuilder.buildTbtFrame(
            type = 1,
            maneuverIcon = Maneuver.DESTINATION_REACHED.opcode.toInt(),
            stepMeters = 0,
            totalMeters = 0,
            streetText = "Destination"
        )
        assertEquals(Maneuver.DESTINATION_REACHED.opcode, arrived[1])
        assertChecksumValid(arrived, 47)
    }

    @Test
    fun buildTbtFrame_transliteratesDiacriticsAndTruncatesWithin31Bytes() {
        val frame = OtcPacketBuilder.buildTbtFrame(
            type = 1,
            maneuverIcon = Maneuver.STRAIGHT.opcode.toInt(),
            stepMeters = 1400,
            totalMeters = 2300,
            streetText = "Avenida José Niño de Guzmán Súper Larga"
        )
        val len = frame[14].toInt() and 0xFF
        assertTrue("Expected street len <= 31, got $len", len in 1..31)
        val street = String(frame, 15, len, StandardCharsets.US_ASCII)
        assertTrue(street.startsWith("Avenida Jose Nino"))
        assertTrue(street.endsWith("."))
        assertChecksumValid(frame, 47)
    }

    @Test
    fun formatDistance_usesIntegerHundredthsWithoutFloatDrift() {
        // 1400m -> 1.40 km (unit = 0, i = 1, frac = 40)
        val d1400 = OtcPacketBuilder.formatDistance(1400.0)
        assertEquals(0, d1400[0].toInt())
        assertEquals(1, ((d1400[1].toInt() and 0xFF) shl 8) or (d1400[2].toInt() and 0xFF))
        assertEquals(40, ((d1400[3].toInt() and 0xFF) shl 8) or (d1400[4].toInt() and 0xFF))

        // 2300m -> 2.30 km (unit = 0, i = 2, frac = 30)
        val d2300 = OtcPacketBuilder.formatDistance(2300.0)
        assertEquals(0, d2300[0].toInt())
        assertEquals(2, ((d2300[1].toInt() and 0xFF) shl 8) or (d2300[2].toInt() and 0xFF))
        assertEquals(30, ((d2300[3].toInt() and 0xFF) shl 8) or (d2300[4].toInt() and 0xFF))

        // 999m -> 999 m (unit = 1, i = 999, frac = 0)
        val d999 = OtcPacketBuilder.formatDistance(999.0)
        assertEquals(1, d999[0].toInt())
        assertEquals(999, ((d999[1].toInt() and 0xFF) shl 8) or (d999[2].toInt() and 0xFF))
        assertEquals(0, ((d999[3].toInt() and 0xFF) shl 8) or (d999[4].toInt() and 0xFF))
    }

    @Test
    fun buildGeneralPacketV2_encodesCallAndChecksumAndIncrementsCounter() {
        val p1 = OtcPacketBuilder.buildGeneralPacketV2(
            batteryPercent = 82,
            volumeLevel = 10,
            signalBars = 3,
            callState = CallState.INCOMING_CALL,
            callerName = "Dad",
            missedCallCount = 2
        )
        val p2 = OtcPacketBuilder.buildGeneralPacketV2(
            batteryPercent = 82,
            volumeLevel = 10,
            signalBars = 3,
            callState = CallState.NO_CALL
        )
        assertEquals(OtcPacketBuilder.GENERAL_PACKET_SIZE, p1.size)
        assertEquals((0xC0 or 10).toByte(), p1[0])
        assertEquals(((3 shl 4) or CallState.INCOMING_CALL.value).toByte(), p1[1])
        assertEquals(3.toByte(), p1[2])
        assertEquals(2.toByte(), p1[13])
        assertEquals(1.toByte(), p1[18])
        assertEquals(6.toByte(), p1[19]) // "Dad   " padded short name
        assertChecksumValid(p1, 88)
        assertChecksumValid(p2, 88)
        assertNotEquals(p1[54], p2[54])
    }

    @Test
    fun buildGeneralPacketLegacy_has55BytesAndAcceptsCallAcks() {
        val frame = OtcPacketBuilder.buildGeneralPacketLegacy(
            batteryPercent = 65,
            callState = CallState.ACTIVE_CALL,
            callerName = "Mom",
            callRejectAck = 4,
            callRejectSmsAck = 1,
            missedCallCount = 3
        )
        assertEquals(OtcPacketBuilder.GENERAL_LEGACY_SIZE, frame.size)
        assertEquals(1.toByte(), frame[3])
        assertEquals(0.toByte(), frame[4])
        assertEquals(4.toByte(), frame[5])
        assertEquals(1.toByte(), frame[6])
        assertEquals(3.toByte(), frame[13])
        assertChecksumValid(frame, 54)
    }

    @Test
    fun buildGeneralPacketV2_encodesTftStatusAndSwitchgearAcks() {
        val frame = OtcPacketBuilder.buildGeneralPacketV2(
            batteryPercent = 85,
            volumeLevel = 4,
            signalBars = 5,
            callState = CallState.NO_CALL,
            isHeadsetConnected = true,
            isDndEnabled = true,
            resumeSongAck = 1,
            pauseSongAck = 2,
            skipToNextAck = 3,
            skipToPrevAck = 4,
            stopSongAck = 5,
            launchMediaPlayerAck = 6
        )
        assertEquals(OtcPacketBuilder.GENERAL_PACKET_SIZE, frame.size)
        // b[0] = (4 & 0x0F) | 0x10 (headset) | 0xC0 = 0xD4
        assertEquals(0xD4.toByte(), frame[0])
        // b[1] = 0x80 (DND) | (3 << 4) (batBars >= 75%) | 0 = 0xB0
        assertEquals(0xB0.toByte(), frame[1])
        assertEquals(5.toByte(), frame[2])
        assertEquals(1.toByte(), frame[7])
        assertEquals(2.toByte(), frame[8])
        assertEquals(3.toByte(), frame[9])
        assertEquals(4.toByte(), frame[10])
        assertEquals(5.toByte(), frame[11])
        assertEquals(6.toByte(), frame[12])
        assertChecksumValid(frame, OtcPacketBuilder.GENERAL_PACKET_SIZE - 1)
    }

    @Test
    fun buildMusicInfoFrame_matches111ByteTftWireLayout() {
        val frame = OtcPacketBuilder.buildMusicInfoFrame(
            title = "Hello old friend! feat Pulsar NS",
            artist = "Kartikeya",
            album = "Kartikeya Singhee",
            elapsedSeconds = 375,
            durationSeconds = 1429,
            playStatus = 2
        )
        assertEquals(OtcPacketBuilder.MUSIC_INFO_PACKET_SIZE, frame.size)
        assertEquals(1.toByte(), frame[0])
        assertEquals(32.toByte(), frame[1])
        assertEquals(
            "Hello old friend! feat Pulsar NS",
            String(frame, 2, 32, StandardCharsets.UTF_8)
        )
        assertEquals(9.toByte(), frame[34])
        assertEquals("Kartikeya", String(frame, 35, 9, StandardCharsets.UTF_8))
        assertEquals(17.toByte(), frame[67])
        assertEquals("Kartikeya Singhee", String(frame, 68, 17, StandardCharsets.UTF_8))
        assertEquals(0x01.toByte(), frame[100])
        assertEquals(0x77.toByte(), frame[101])
        assertEquals(0x05.toByte(), frame[102])
        assertEquals(0x95.toByte(), frame[103])
        assertEquals(2.toByte(), frame[104])
        for (i in 105 until OtcPacketBuilder.MUSIC_INFO_PACKET_SIZE) {
            assertEquals("Expected reserved zero byte at $i", 0, frame[i].toInt())
        }
    }

    @Test
    fun parseControlFrame_decodesTftSwitchgearVolumeAndRejectsShortFrames() {
        assertNull(OtcPacketBuilder.parseControlFrame(ByteArray(17)))

        val resumeRaw = ByteArray(26)
        resumeRaw[0] = 0x04
        resumeRaw[6] = 0x01
        val resumeFrame = OtcPacketBuilder.parseControlFrame(resumeRaw)
        assertNotNull(resumeFrame)
        assertEquals(4, resumeFrame!!.volumeToSet)
        assertEquals(1, resumeFrame.resumeSong)
        assertEquals(0, resumeFrame.pauseSong)

        val pauseRaw = ByteArray(26)
        pauseRaw[0] = 0x03
        pauseRaw[7] = 0x01
        pauseRaw[8] = 0x02
        pauseRaw[9] = 0x03
        val pauseFrame = OtcPacketBuilder.parseControlFrame(pauseRaw)
        assertNotNull(pauseFrame)
        assertEquals(3, pauseFrame!!.volumeToSet)
        assertEquals(1, pauseFrame.pauseSong)
        assertEquals(2, pauseFrame.skipToNext)
        assertEquals(3, pauseFrame.skipToPrev)

        val volTwoRaw = ByteArray(26)
        volTwoRaw[0] = 0x02
        val volTwoFrame = OtcPacketBuilder.parseControlFrame(volTwoRaw)
        assertEquals(2, volTwoFrame!!.volumeToSet)
    }

    @Test
    fun buildWhatsAppAlertPacket_matches44ByteOemLayoutAndClampsLength() {
        val packet = OtcPacketBuilder.buildWhatsAppAlertPacket("A".repeat(40))
        assertEquals(OtcPacketBuilder.ALERT_PACKET_SIZE, packet.size)
        assertEquals(AlertType.WHATSAPP.value, packet[0])
        assertEquals(32.toByte(), packet[1])
        assertEquals("A".repeat(32), String(packet, 2, 32, StandardCharsets.UTF_8))
        val ts = ((packet[34].toLong() and 0xFF) shl 24) or
            ((packet[35].toLong() and 0xFF) shl 16) or
            ((packet[36].toLong() and 0xFF) shl 8) or
            (packet[37].toLong() and 0xFF)
        assertTrue("Expected positive big-endian epoch seconds, got $ts", ts > 1_700_000_000L)
        for (i in 38 until OtcPacketBuilder.ALERT_PACKET_SIZE) {
            assertEquals("Expected zero padding at $i", 0, packet[i].toInt())
        }
    }

    @Test
    fun buildMissedCallPacket_matches63ByteOemLayout() {
        val packet = OtcPacketBuilder.buildMissedCallPacket("Mom", "+91 98765-43210")
        assertEquals(OtcPacketBuilder.MISSED_CALL_PACKET_SIZE, packet.size)
        assertEquals(1.toByte(), packet[0])
        assertEquals(3.toByte(), packet[1])
        assertEquals("Mom", String(packet, 2, 3, StandardCharsets.UTF_8))
        assertEquals(13.toByte(), packet[34])
        assertEquals("+919876543210", String(packet, 35, 13, StandardCharsets.US_ASCII))
        for (i in 57 until OtcPacketBuilder.MISSED_CALL_PACKET_SIZE) {
            assertEquals("Expected zero padding at $i", 0, packet[i].toInt())
        }
    }

    @Test
    fun buildFavContactsAndRecentCallsFrames_match151ByteOemWireLayout() {
        val favs = listOf(
            ClusterContactEntry(name = "Dad (Mobile)", number = "+91 98765 43210"),
            ClusterContactEntry(name = "Mom", number = "9123456789")
        )
        val favFrame = OtcPacketBuilder.buildFavContactsFrame(favs)
        assertEquals(OtcPacketBuilder.FAV_CONTACTS_PACKET_SIZE, favFrame.size)
        assertEquals(0xC0.toByte(), favFrame[0])
        assertEquals(0x20.toByte(), favFrame[1])
        assertEquals(10.toByte(), favFrame[2])
        assertEquals("Dad (Mobil", String(favFrame, 3, 10, StandardCharsets.US_ASCII))
        assertEquals(12.toByte(), favFrame[13])
        assertEquals("919876543210", String(favFrame, 14, 12, StandardCharsets.US_ASCII))
        assertEquals(3.toByte(), favFrame[29])
        assertEquals("Mom", String(favFrame, 30, 3, StandardCharsets.US_ASCII))
        assertChecksumValid(favFrame, 150)

        val recents = listOf(
            ClusterRecentCallEntry("ServiceCtr", "+91-8000011111", RecentCallStatus.OUTGOING),
            ClusterRecentCallEntry("Rider Two", "9998887776", RecentCallStatus.MISSED),
            ClusterRecentCallEntry("Friend", "9777666555", RecentCallStatus.INCOMING)
        )
        val recentFrame = OtcPacketBuilder.buildRecentCallsFrame(recents)
        assertEquals(OtcPacketBuilder.RECENT_CALLS_PACKET_SIZE, recentFrame.size)
        assertEquals(0x80.toByte(), recentFrame[0])
        assertEquals(0x30.toByte(), recentFrame[1])
        assertEquals(RecentCallStatus.OUTGOING.value.toByte(), recentFrame[137])
        assertEquals(RecentCallStatus.MISSED.value.toByte(), recentFrame[138])
        assertEquals(RecentCallStatus.INCOMING.value.toByte(), recentFrame[139])
        assertEquals(0.toByte(), recentFrame[140])
        assertChecksumValid(recentFrame, 150)
    }

    @Test
    fun buildPlaylistAndResetFrames_match177ByteOemWireLayout() {
        val frame = OtcPacketBuilder.buildPlaylistFrame(
            trackTitles = listOf("Hello old friend! feat Pulsar NS", "Night Ride"),
            totalSongs = 7,
            totalPages = 2,
            currentTrackNumber = 1,
            pagePlaylistAck = 2,
            newPlaylistAck = 1,
            userChangePlaylistCounter = 10
        )
        assertEquals(OtcPacketBuilder.PLAYLIST_PACKET_SIZE, frame.size)
        assertEquals(((1 shl 6) or 2).toByte(), frame[0])
        assertEquals(7.toByte(), frame[1])
        assertEquals(2.toByte(), frame[2])
        assertEquals(1.toByte(), frame[3])
        assertEquals(10.toByte(), frame[4])
        assertEquals(0x1F.toByte(), frame[5])
        assertEquals(32.toByte(), frame[6])
        assertEquals("Hello old friend! feat Pulsar NS", String(frame, 7, 32, StandardCharsets.UTF_8))
        assertEquals(10.toByte(), frame[39])
        assertEquals("Night Ride", String(frame, 40, 10, StandardCharsets.UTF_8))

        val reset = OtcPacketBuilder.buildResetPlaylistFrame(pagePlaylistAck = 1, newPlaylistAck = 2, userChangePlaylistCounter = 4)
        assertEquals(OtcPacketBuilder.PLAYLIST_PACKET_SIZE, reset.size)
        assertEquals(((2 shl 6) or 1).toByte(), reset[0])
        assertEquals(4.toByte(), reset[4])
        assertEquals(0x1F.toByte(), reset[5])
        assertEquals(0.toByte(), reset[1])
        assertEquals(0.toByte(), reset[6])
    }

    @Test
    fun parseControlFrame_decodesDialSourceIndexTxnPlaylistAndTakeMeHome() {
        val raw = ByteArray(26)
        raw[0] = ((2 shl 6) or 7).toByte() // dialSource=2 (Recent), volume=7
        raw[3] = 5 // callRejectWithSms
        raw[4] = ((1 shl 6) or 3).toByte() // newPlaylistReq=1, pagePlaylist=3
        raw[5] = 2 // takeMeHome
        raw[11] = 4 // missedCallGet
        raw[12] = 9 // alertGet
        raw[14] = 2 // selectPlaylistSongReq
        raw[15] = 4 // selectedPlaylistSong
        raw[16] = 3 // dialIndex
        raw[17] = 11 // dialTxn

        val frame = OtcPacketBuilder.parseControlFrame(raw)!!
        assertEquals(7, frame.volumeToSet)
        assertEquals(2, frame.dialSource)
        assertEquals(5, frame.callRejectWithSms)
        assertEquals(3, frame.pagePlaylist)
        assertEquals(1, frame.newPlaylistReq)
        assertEquals(2, frame.takeMeHome)
        assertEquals(4, frame.missedCallGet)
        assertEquals(9, frame.alertGet)
        assertEquals(2, frame.selectPlaylistSongReq)
        assertEquals(4, frame.selectedPlaylistSong)
        assertEquals(3, frame.dialIndex)
        assertEquals(11, frame.dialTxn)

        val tbtWithHomeAck = OtcPacketBuilder.buildTbtFrame(
            type = 1,
            maneuverIcon = Maneuver.STRAIGHT.opcode.toInt(),
            stepMeters = 200,
            totalMeters = 5000,
            takeMeHomeAck = 2
        )
        assertEquals(2.toByte(), tbtWithHomeAck[13])
        assertChecksumValid(tbtWithHomeAck, 47)

        val outgoing = OtcPacketBuilder.buildGeneralPacketV2(
            batteryPercent = 80,
            volumeLevel = 5,
            signalBars = 4,
            callState = CallState.OUTGOING_CALL,
            callerName = "Dad"
        )
        assertEquals(1.toByte(), outgoing[18])
        assertChecksumValid(outgoing, 88)
    }

    @Test
    fun vehicleCatalog_containsNs400zProfile() {
        val model = VehicleCatalog.getModelById("ns400z")
        assertEquals("ns400z", model.id)
        assertEquals("Pulsar NS400Z", model.name)
    }

    private fun assertChecksumValid(packet: ByteArray, checksumIndex: Int) {
        var sum = 0
        for (i in 0 until checksumIndex) {
            sum = (sum + (packet[i].toInt() and 0xFF)) and 0xFF
        }
        assertEquals("Checksum mismatch at byte $checksumIndex", sum, packet[checksumIndex].toInt() and 0xFF)
    }
}

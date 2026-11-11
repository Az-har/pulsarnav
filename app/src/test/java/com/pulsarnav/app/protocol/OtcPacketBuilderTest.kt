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

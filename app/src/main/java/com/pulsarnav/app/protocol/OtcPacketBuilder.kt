package com.pulsarnav.app.protocol

import android.os.Build
import com.pulsarnav.app.model.Maneuver
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.round

enum class CallState(val value: Int) {
    NO_CALL(0),
    INCOMING_CALL(1),
    OUTGOING_CALL(2),
    ACTIVE_CALL(3),
    END_CALL(4)
}

enum class AlertType(val value: Byte) {
    SMS(1),
    WHATSAPP(2)
}

/**
 * Decoded handlebar/switchgear & volume control state from characteristic 0a10676e-...
 * (18B legacy LCD or 20..26B TFT such as Pulsar NS400Z).
 */
data class OtcControlFrame(
    val volumeToSet: Int,
    val dialSource: Int,
    val callAccept: Int,
    val callReject: Int,
    val callRejectWithSms: Int,
    val takeMeHome: Int,
    val resumeSong: Int,
    val pauseSong: Int,
    val skipToNext: Int,
    val skipToPrev: Int,
    val stopSong: Int,
    val missedCallGet: Int,
    val alertGet: Int,
    val launchMediaPlayer: Int,
    val selectPlaylistSongReq: Int,
    val selectedPlaylistSong: Int,
    val dialIndex: Int,
    val dialTxn: Int
)

/**
 * Encodes turn-by-turn navigation, general telemetry heartbeat, caller ID, and WhatsApp notifications
 * into byte-for-byte verified BLE packet frames for the Bajaj Pulsar NS200 / NS400Z / Dominar digital clusters.
 *
 * Reverse-engineered from official Bajaj Ride Connect (com.bajajconnect.rideapp v1.11.57 - v1.11.80)
 * and verified by manov-ik/ride-relay.
 */
object OtcPacketBuilder {

    const val TBT_PACKET_SIZE = 48
    const val GENERAL_PACKET_SIZE = 89
    const val GENERAL_LEGACY_SIZE = 55
    const val ALERT_PACKET_SIZE = 44
    const val MISSED_CALL_PACKET_SIZE = 63
    const val MUSIC_INFO_PACKET_SIZE = 111

    private const val MAX_STREET_LEN = 31
    private const val MAX_NAME_LEN = 32
    private const val MAX_PHONE_LEN = 18

    private val heartbeatCounter = AtomicInteger(0)

    private fun nextHeartbeatByte(): Byte =
        (heartbeatCounter.incrementAndGet() and 0xFF).toByte()

    // =========================================================================
    // 1. Turn-by-Turn Navigation Frame (48 bytes, Characteristic 0110676e-...)
    // =========================================================================

    /**
     * Builds the verified 48-byte turn-by-turn frame.
     *
     * Byte Layout:
     *  0     1    flags: bit0 = frame type (1 = active nav update, 0 = nav stop)
     *                    bits4-7 = distance unit for STEP distance (1 = metres, 0 = km)
     *                    bit7 = 1 if ETA is PM, 0 if AM
     *  1     1    maneuver icon id (66..90 base; +32 = 98..122 blinking when < 100m)
     *  2-5   4    STEP distance in little-endian decimal integer + hundredths (fLow, fHigh, iLow, iHigh)
     *  6     1    ETA minute (0-59)
     *  7     1    ETA hour (1-12) | (roundabout exit number << 4)
     *  8-11  4    TOTAL distance in little-endian decimal integer + hundredths (fLow, fHigh, iLow, iHigh)
     * 12     1    bit0 = total-dist unit (1 = metres, 0 = km) | bits2-3 = GPS active (1)
     * 13     1    takeMeHome ack (0)
     * 14     1    street/instruction text length (max 31)
     * 15-45 31    street/instruction text ASCII
     * 46     1    unused (0)
     * 47     1    checksum = sum(bytes[0..46]) & 0xFF
     */
    fun buildTbtFrame(
        type: Int = 1,
        maneuverIcon: Int,
        isAm: Boolean = true,
        stepMeters: Int,
        totalMeters: Int,
        etaHour: Int = 12,
        etaMinute: Int = 0,
        roundaboutExit: Int = 0,
        streetText: String? = null
    ): ByteArray {
        val step = formatDistance(stepMeters.coerceAtLeast(0).toDouble())
        val total = formatDistance(totalMeters.coerceAtLeast(0).toDouble())

        var t = transliterateAscii(streetText)
            .replace(Regex("[^0-9a-zA-Z.]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.length > MAX_STREET_LEN) {
            t = t.substring(0, MAX_STREET_LEN - 1).trimEnd() + "."
        }

        val b = ByteArray(TBT_PACKET_SIZE)

        // Byte 0: type(bit0) | step-unit(bit4, 1=metres) | PM(bit7)
        var b0 = type and 0x01
        b0 = b0 or ((step[0].toInt() and 0x01) shl 4)
        b0 = b0 or ((if (isAm) 0 else 1) shl 7)
        b[0] = b0.toByte()

        // Byte 1: Maneuver Icon ID
        // The cluster adds 32 for the blinking variant when turn is < 100m (active updates only)
        var icon = maneuverIcon
        if (type != 0 && stepMeters in 0..99 && icon > 0 &&
            icon != Maneuver.DESTINATION_REACHED.opcode.toInt() && icon < 98
        ) {
            icon += 32
        }
        b[1] = (icon and 0xFF).toByte()

        // Bytes 2-5: Step distance (decimal integer + hundredths stored as LE shorts)
        b[2] = step[4]
        b[3] = step[3]
        b[4] = step[2]
        b[5] = step[1]

        // Byte 6: ETA minute (0-59)
        b[6] = (etaMinute.coerceIn(0, 59) and 0xFF).toByte()

        // Byte 7: ETA hour (1-12 for active updates; 0 allowed for stop frames) | (roundabout exit << 4)
        val validHour = if (type == 0) {
            etaHour.coerceIn(0, 12)
        } else if (etaHour in 1..12) {
            etaHour
        } else {
            12
        }
        b[7] = ((validHour and 0x0F) or ((roundaboutExit.coerceIn(0, 15) and 0x0F) shl 4)).toByte()

        // Bytes 8-11: Total distance (decimal integer + hundredths stored as LE shorts)
        b[8] = total[4]
        b[9] = total[3]
        b[10] = total[2]
        b[11] = total[1]

        // Byte 12: total-unit(bit0) | gps status(bits2-3, 1 = GPS active when type != 0)
        val gpsActive = if (type == 0) 0 else 1
        b[12] = ((total[0].toInt() and 0x01) or (gpsActive shl 2)).toByte()

        // Byte 13: takeMeHome ack
        b[13] = 0

        // Bytes 14-45: Street length and ASCII content
        if (t.isNotEmpty()) {
            val tb = t.toByteArray(StandardCharsets.US_ASCII)
            val n = tb.size.coerceAtMost(MAX_STREET_LEN)
            b[14] = n.toByte()
            System.arraycopy(tb, 0, b, 15, n)
        } else {
            b[14] = 0
        }

        // Byte 46: Unused (0)
        b[46] = 0

        // Byte 47: Checksum of bytes 0..46
        var sum = 0
        for (i in 0 until 47) {
            sum = (sum + (b[i].toInt() and 0xFF)) and 0xFF
        }
        b[47] = sum.toByte()

        return b
    }

    /**
     * Nav stop frame: type 0, all parameters zeroed, valid checksum (0).
     * Clears turn-by-turn navigation graphics on the bike's LCD.
     */
    fun buildTbtStopFrame(): ByteArray {
        return ByteArray(TBT_PACKET_SIZE)
    }

    /**
     * Encodes distance matching Bajaj NavigationHelper.formatDistance:
     * Returns 5 bytes: [unit, iHigh, iLow, fracHigh, fracLow]
     *  - If d < 1000m: value in metres, unit = 1
     *  - If d >= 1000m: value in km, unit = 0, two decimal places
     */
    fun formatDistance(d: Double): ByteArray {
        val clamped = d.coerceAtLeast(0.0)
        val unit: Int
        val i: Int
        val frac: Int
        if (clamped < 999.5) {
            unit = 1
            i = round(clamped).toInt()
            frac = 0
        } else {
            unit = 0
            val totalHundredths = round((clamped / 1000.0) * 100.0).toInt()
            i = totalHundredths / 100
            frac = totalHundredths % 100
        }
        return byteArrayOf(
            unit.toByte(),
            ((i shr 8) and 0xFF).toByte(),
            (i and 0xFF).toByte(),
            ((frac shr 8) and 0xFF).toByte(),
            (frac and 0xFF).toByte()
        )
    }

    // =========================================================================
    // 2. General Status Packet (89 bytes / 55 bytes, Characteristic 0210676e-...)
    // =========================================================================

    /**
     * Builds 89-byte General Status Packet (V2).
     * Sent after MTU negotiation and every 5 seconds as the link heartbeat.
     */
    fun buildGeneralPacketV2(
        batteryPercent: Int,
        volumeLevel: Int = 8,
        signalBars: Int = 4,
        callState: CallState = CallState.NO_CALL,
        callerName: String? = null,
        callAcceptAck: Int = 0,
        callRejectAck: Int = 0,
        callRejectSmsAck: Int = 0,
        missedCallCount: Int = 0,
        missedCallGetAck: Int = 0,
        callProgress: Int = 0,
        isHeadsetConnected: Boolean = false,
        isDndEnabled: Boolean = false,
        resumeSongAck: Int = 0,
        pauseSongAck: Int = 0,
        skipToNextAck: Int = 0,
        skipToPrevAck: Int = 0,
        stopSongAck: Int = 0,
        launchMediaPlayerAck: Int = 0,
        smsCount: Int = 0,
        alertsGetAck: Int = 0,
        selectPlaylistSongAck: Int = 0
    ): ByteArray {
        val counter = nextHeartbeatByte()
        val b = ByteArray(GENERAL_PACKET_SIZE)

        // [0] Volume (low nibble 0..15) | headset connected (bit 4) | 0xC0 marker
        val headsetBit = if (isHeadsetConnected) 0x10 else 0
        b[0] = ((volumeLevel.coerceIn(0, 15) and 0x0F) or headsetBit or 0xC0).toByte()

        // [1] DND flag (bit 7) | Battery coarse bars 0..3 (bits 4-5) | Call state (bits 0-3)
        val batBars = when {
            batteryPercent >= 75 -> 3
            batteryPercent >= 50 -> 2
            batteryPercent >= 25 -> 1
            else -> 0
        }
        val dndBit = if (isDndEnabled) 0x80 else 0
        b[1] = (dndBit or ((batBars and 0x03) shl 4) or (callState.value and 0x0F)).toByte()

        // [2] Signal bars 0..7 (bits 0-2)
        b[2] = (signalBars.coerceIn(0, 7) and 0x07).toByte()

        // [3] Birthday month (0)
        b[3] = 0

        // [4..6] Call action acks
        b[4] = (callAcceptAck and 0xFF).toByte()
        b[5] = (callRejectAck and 0xFF).toByte()
        b[6] = (callRejectSmsAck and 0xFF).toByte()

        // [7..12] Music / switchgear action acks (TFT / V2 clusters)
        b[7] = (resumeSongAck and 0xFF).toByte()
        b[8] = (pauseSongAck and 0xFF).toByte()
        b[9] = (skipToNextAck and 0xFF).toByte()
        b[10] = (skipToPrevAck and 0xFF).toByte()
        b[11] = (stopSongAck and 0xFF).toByte()
        b[12] = (launchMediaPlayerAck and 0xFF).toByte()

        // [13..17] Missed calls, SMS & playlist/alert acks
        b[13] = (missedCallCount.coerceIn(0, 255)).toByte()
        b[14] = (missedCallGetAck and 0xFF).toByte()
        b[15] = (smsCount.coerceIn(0, 255)).toByte()
        b[16] = (alertsGetAck and 0xFF).toByte()
        b[17] = (selectPlaylistSongAck and 0xFF).toByte()

        // [18..51] Caller ID info
        val hasCaller = (callState == CallState.INCOMING_CALL || callState == CallState.ACTIVE_CALL)
        if (hasCaller && !callerName.isNullOrBlank()) {
            b[18] = 1
            var clean = sanitizeAscii(callerName, MAX_NAME_LEN)
            // If caller name is short (e.g. "Dad"), pad with trailing spaces so the cluster LCD
            // scrolls "Dad   Dad   " instead of looping seamlessly into "DADADADAD"
            if (clean.isNotEmpty() && clean.length <= 6) {
                clean = "$clean   "
            }
            val bytes = clean.toByteArray(StandardCharsets.UTF_8)
            val len = bytes.size.coerceAtMost(MAX_NAME_LEN)
            System.arraycopy(bytes, 0, b, 20, len)
            b[19] = len.toByte()
        } else {
            b[18] = 0
        }

        // [52..53] Call progress counter (LE)
        b[52] = (callProgress and 0xFF).toByte()
        b[53] = ((callProgress shr 8) and 0xFF).toByte()

        // [54] Heartbeat counter (increments every packet)
        b[54] = counter

        // [55..87] Phone model string
        val model = sanitizeAscii(Build.MODEL ?: "Android", 32)
        val mBytes = model.toByteArray(StandardCharsets.UTF_8)
        val mLen = mBytes.size.coerceAtMost(32)
        b[55] = mLen.toByte()
        System.arraycopy(mBytes, 0, b, 56, mLen)

        // [88] Checksum (sum of 0..87) & 0xFF
        var sum = 0
        for (i in 0 until 88) {
            sum = (sum + (b[i].toInt() and 0xFF)) and 0xFF
        }
        b[88] = sum.toByte()

        return b
    }

    /**
     * Builds 55-byte Legacy General Status Packet.
     */
    fun buildGeneralPacketLegacy(
        batteryPercent: Int,
        volumeLevel: Int = 8,
        callState: CallState = CallState.NO_CALL,
        callerName: String? = null,
        callRejectAck: Int = 0,
        callRejectSmsAck: Int = 0,
        missedCallCount: Int = 0,
        missedCallGetAck: Int = 0
    ): ByteArray {
        val counter = nextHeartbeatByte()
        val b = ByteArray(GENERAL_LEGACY_SIZE)

        b[0] = ((volumeLevel.coerceIn(0, 15) and 0x0F) or 0xC0).toByte()

        val batBars = when {
            batteryPercent >= 80 -> 4
            batteryPercent >= 60 -> 3
            batteryPercent >= 40 -> 2
            batteryPercent >= 20 -> 1
            else -> 0
        }
        b[1] = (((batBars and 0x07) shl 3) or (callState.value and 0x0F)).toByte()

        b[2] = 4 // signal default

        b[3] = if (callState == CallState.ACTIVE_CALL) 1 else 0
        b[4] = if (callState == CallState.ACTIVE_CALL) 0 else 1

        b[5] = (callRejectAck and 0xFF).toByte()
        b[6] = (callRejectSmsAck and 0xFF).toByte()
        b[13] = (missedCallCount.coerceIn(0, 255)).toByte()
        b[14] = (missedCallGetAck and 0xFF).toByte()
        b[17] = 0

        val hasCaller = (callState == CallState.INCOMING_CALL || callState == CallState.ACTIVE_CALL)
        if (hasCaller && !callerName.isNullOrBlank()) {
            b[18] = 1
            var clean = sanitizeAscii(callerName, 30)
            if (clean.isNotEmpty() && clean.length <= 6) {
                clean = "$clean   "
            }
            val bytes = clean.toByteArray(StandardCharsets.UTF_8)
            val len = bytes.size.coerceAtMost(30)
            System.arraycopy(bytes, 0, b, 21, len)
            b[20] = len.toByte()
        } else {
            b[18] = 0
        }

        b[53] = counter
        return b
    }

    // =========================================================================
    // 3. Alerts & Missed Call Frames
    // =========================================================================

    /**
     * Builds 44-byte WhatsApp Alert Packet for characteristic 0410676e-...
     */
    fun buildWhatsAppAlertPacket(senderName: String): ByteArray {
        val packet = ByteArray(ALERT_PACKET_SIZE)
        packet[0] = AlertType.WHATSAPP.value

        val cleanName = sanitizeAscii(senderName, MAX_NAME_LEN)
        val nameBytes = cleanName.toByteArray(StandardCharsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(MAX_NAME_LEN)
        packet[1] = nameLen.toByte()
        System.arraycopy(nameBytes, 0, packet, 2, nameLen)

        val epochSec = System.currentTimeMillis() / 1000L
        packet[34] = ((epochSec shr 24) and 0xFF).toByte()
        packet[35] = ((epochSec shr 16) and 0xFF).toByte()
        packet[36] = ((epochSec shr 8) and 0xFF).toByte()
        packet[37] = (epochSec and 0xFF).toByte()

        return packet
    }

    /**
     * Builds 40-byte Legacy Alert Packet for characteristic 0410676e-...
     * Compatible with older Bajaj instrument cluster firmware.
     */
    fun buildWhatsAppAlertLegacyPacket(senderName: String): ByteArray {
        val packet = ByteArray(40)
        packet[0] = AlertType.WHATSAPP.value

        val cleanName = sanitizeAscii(senderName, 32)
        val nameBytes = cleanName.toByteArray(StandardCharsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(32)
        packet[1] = nameLen.toByte()
        System.arraycopy(nameBytes, 0, packet, 2, nameLen)

        return packet
    }

    /**
     * Builds 63-byte Missed Call Packet for characteristic 0310676e-...
     */
    fun buildMissedCallPacket(callerName: String, callerNumber: String): ByteArray {
        val packet = ByteArray(MISSED_CALL_PACKET_SIZE)
        packet[0] = 0x01

        val cleanName = sanitizeAscii(callerName, MAX_NAME_LEN)
        val nameBytes = cleanName.toByteArray(StandardCharsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(MAX_NAME_LEN)
        packet[1] = nameLen.toByte()
        System.arraycopy(nameBytes, 0, packet, 2, nameLen)

        val cleanPhone = sanitizePhone(callerNumber)
        val phoneBytes = cleanPhone.toByteArray(StandardCharsets.US_ASCII)
        val phoneLen = phoneBytes.size.coerceAtMost(MAX_PHONE_LEN)
        packet[34] = phoneLen.toByte()
        System.arraycopy(phoneBytes, 0, packet, 35, phoneLen)

        val epochSec = System.currentTimeMillis() / 1000L
        packet[53] = ((epochSec shr 24) and 0xFF).toByte()
        packet[54] = ((epochSec shr 16) and 0xFF).toByte()
        packet[55] = ((epochSec shr 8) and 0xFF).toByte()
        packet[56] = (epochSec and 0xFF).toByte()

        return packet
    }

    // =========================================================================
    // 5. TFT Media Info (0610) & Switchgear Control Frame Parser (0a10)
    // =========================================================================

    /**
     * Builds the 111-byte Media Info frame (Characteristic 0610676e-...) for Bajaj TFT clusters
     * such as Pulsar NS400Z.
     *
     * Byte Layout (verified against CallFrame.musicInfoFrame & PULSAR1854 wire captures):
     *  0       1    header flag (1)
     *  1       1    title byte length (0..32)
     *  2..33   32   title UTF-8/ASCII bytes (zero-padded)
     *  34      1    artist byte length (0..32)
     *  35..66  32   artist UTF-8/ASCII bytes (zero-padded)
     *  67      1    album byte length (0..32)
     *  68..99  32   album UTF-8/ASCII bytes (zero-padded)
     *  100..101 2   elapsed playback time in seconds (uint16 big-endian)
     *  102..103 2   track duration in seconds (uint16 big-endian)
     *  104     1    play status (0 = stopped/none, 1 = paused, 2 = playing)
     *  105..110 6   reserved zeros
     */
    fun buildMusicInfoFrame(
        title: String,
        artist: String = "",
        album: String = "",
        elapsedSeconds: Int = 0,
        durationSeconds: Int = 0,
        playStatus: Int = 0
    ): ByteArray {
        val b = ByteArray(MUSIC_INFO_PACKET_SIZE)
        b[0] = 1

        writeUtf8Field32(b, lenIndex = 1, dataOffset = 2, value = sanitizeAscii(title, MAX_NAME_LEN))
        writeUtf8Field32(b, lenIndex = 34, dataOffset = 35, value = sanitizeAscii(artist, MAX_NAME_LEN))
        writeUtf8Field32(b, lenIndex = 67, dataOffset = 68, value = sanitizeAscii(album, MAX_NAME_LEN))

        val elapsed = elapsedSeconds.coerceIn(0, 0xFFFF)
        b[100] = ((elapsed ushr 8) and 0xFF).toByte()
        b[101] = (elapsed and 0xFF).toByte()

        val duration = durationSeconds.coerceIn(0, 0xFFFF)
        b[102] = ((duration ushr 8) and 0xFF).toByte()
        b[103] = (duration and 0xFF).toByte()

        b[104] = playStatus.coerceIn(0, 2).toByte()
        return b
    }

    /**
     * Parses characteristic 0a10 control notifications/reads (18B LCD or 20..26B TFT).
     */
    fun parseControlFrame(b: ByteArray): OtcControlFrame? {
        if (b.size < 18) return null
        val b0 = b[0].toInt() and 0xFF
        return OtcControlFrame(
            volumeToSet = b0 and 0x0F,
            dialSource = (b0 ushr 6) and 0x03,
            callAccept = b[1].toInt() and 0xFF,
            callReject = b[2].toInt() and 0xFF,
            callRejectWithSms = b[3].toInt() and 0xFF,
            takeMeHome = b[5].toInt() and 0xFF,
            resumeSong = b[6].toInt() and 0xFF,
            pauseSong = b[7].toInt() and 0xFF,
            skipToNext = b[8].toInt() and 0xFF,
            skipToPrev = b[9].toInt() and 0xFF,
            stopSong = b[10].toInt() and 0xFF,
            missedCallGet = b[11].toInt() and 0xFF,
            alertGet = b[12].toInt() and 0xFF,
            launchMediaPlayer = b[13].toInt() and 0xFF,
            selectPlaylistSongReq = b[14].toInt() and 0xFF,
            selectedPlaylistSong = b[15].toInt() and 0xFF,
            dialIndex = (b[16].toInt() and 0xFF).coerceIn(0, 4),
            dialTxn = b[17].toInt() and 0xFF
        )
    }

    private fun writeUtf8Field32(dest: ByteArray, lenIndex: Int, dataOffset: Int, value: String) {
        if (value.isEmpty()) return
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val len = bytes.size.coerceAtMost(MAX_NAME_LEN)
        dest[lenIndex] = len.toByte()
        System.arraycopy(bytes, 0, dest, dataOffset, len)
    }

    private fun transliterateAscii(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private fun sanitizeAscii(input: String?, maxLength: Int): String {
        if (input.isNullOrBlank()) return ""
        val cleaned = transliterateAscii(input).replace(Regex("[^\\x20-\\x7E]"), " ").trim()
        return if (cleaned.length > maxLength) cleaned.substring(0, maxLength) else cleaned
    }

    private fun sanitizePhone(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        val digits = phone.filter { it.isDigit() || it == '+' }
        return if (digits.length > MAX_PHONE_LEN) digits.substring(0, MAX_PHONE_LEN) else digits
    }

    fun toHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}

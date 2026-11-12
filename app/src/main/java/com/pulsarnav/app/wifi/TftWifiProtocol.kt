package com.pulsarnav.app.wifi

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin protocol implementation for Bajaj TFT instrument cluster Wi-Fi mirroring
 * (SoftAP WPA2 passphrase derivation, TCP 5558 control/metadata framing, and UDP 5556
 * AES-256-CBC encrypted JPEG chunk streaming).
 *
 * Designed for JVM unit testing without Android framework dependencies.
 */
object TftWifiProtocol {

    const val TFT_WIDTH = 800
    const val TFT_HEIGHT = 480
    const val TFT_DPI = 240

    const val DEFAULT_CLUSTER_HOST = "192.168.4.1"
    const val TCP_PORT = 5558
    const val UDP_PORT = 5556

    const val TCP_HEADER_SIZE = 5
    const val MAX_CLUSTER_REQUEST_PAYLOAD = 2048

    const val UDP_FIRST_CHUNK_BYTES = 1006
    const val UDP_NEXT_CHUNK_BYTES = 1008
    const val UDP_MAX_JPEG_BYTES = 30_000
    const val UDP_TARGET_JPEG_BYTES = 25_000
    const val UDP_MIN_JPEG_QUALITY = 25
    const val UDP_DEFAULT_JPEG_QUALITY = 50
    const val UDP_MAX_REENCODE_ATTEMPTS = 2
    const val UDP_MIN_CAPTURE_INTERVAL_MS = 70L
    const val UDP_TX_PACING_MS = 3L

    const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    const val AES_KEY_STRING = "Z7in5HGAGFdXXU6ZfDc8lIff4iwLY01o"
    val DEFAULT_AES_KEY_BYTES: ByteArray = AES_KEY_STRING.toByteArray(StandardCharsets.UTF_8)

    const val PSK_SALT = "QEAwHxJ+uymNLicUwdhEYwhFj/5zS/sa"
    const val PSK_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    const val PSK_INPUT_BUFFER_BYTES = 50
    const val PSK_OUTPUT_LENGTH = 20

    // Frame types
    const val TYPE_CLUSTER_REQUEST = 0x00
    const val TYPE_PHONE_PUSH = 0x00
    const val TYPE_PHONE_RESPONSE = 0x01

    // Groups
    const val GROUP_INPUT = 0x00
    const val GROUP_SCREEN = 0x01
    const val GROUP_DATA = 0x02
    const val GROUP_POPUP = 0x03

    // Input commands (Group 0x00)
    const val CMD_TOUCH = 0x00
    const val CMD_HANDLEBAR_INPUT = 0x01
    const val HANDLEBAR_RECENTER_MASK = 0x08

    // Screen commands (Group 0x01)
    const val CMD_BIKE_INFO = 0x00
    const val CMD_SCREEN_INFO = 0x01
    const val CMD_SCREEN_CONTROL = 0x02

    const val SCREEN_MODE_CLUSTER = 1
    const val SCREEN_MODE_ROUTE_MAP = 2
    const val SCREEN_MODE_TBT_NAV = 3

    // Data commands (Group 0x02)
    const val CMD_DOC_DATA = 0x00
    const val CMD_DOC_SIZE = 0x01
    const val CMD_DOC_MD5 = 0x02
    const val CMD_DATE_TIME = 0x03
    const val CMD_MIRRORING_STATE = 0x04
    const val CMD_PHONE_NAME = 0x05
    const val CMD_STOP_NAV = 0x06
    const val CMD_NAV_V2_GET_POI_LIST = 0x07
    const val CMD_NAV_V2_GET_POI_DETAIL = 0x08
    const val CMD_NAV_V2_GET_FAVOURITES = 0x09
    const val CMD_NAV_V2_GET_RECENT_TRIPS = 0x0A
    const val CMD_NAV_V2_GET_TRIP_DETAIL = 0x0B
    const val CMD_NAV_V2_GET_ROUTE_PREVIEW = 0x0C
    const val CMD_NAV_V2_SET_ROUTE_PREF = 0x0D
    const val CMD_NAV_V2_GET_ROUTE_PREF = 0x0E
    const val CMD_NAV_V2_START_NAV = 0x0F

    // Popup commands (Group 0x03)
    const val CMD_SHOW_POPUP = 0x00

    // Document indices for CMD_DOC_*
    const val DOC_DL = 0
    const val DOC_RC = 1
    const val DOC_INSURANCE = 2
    const val DOC_MUSIC_COVER = 3
}

/**
 * Derives SoftAP WPA2 passphrases and SSIDs matching the Bajaj TFT cluster algorithm.
 */
object TftPasswordGenerator {

    fun deriveDefaultSsid(bleDeviceName: String?, vin: String?): String {
        val cleanName = bleDeviceName?.trim().orEmpty()
        if (cleanName.startsWith("PULSAR", ignoreCase = true) ||
            cleanName.startsWith("BAJAJ", ignoreCase = true) ||
            cleanName.startsWith("DOMINAR", ignoreCase = true)
        ) {
            return cleanName
        }
        val cleanVin = vin?.trim().orEmpty()
        if (cleanVin.length >= 4) {
            return "PULSAR${cleanVin.takeLast(4).uppercase()}"
        }
        return if (cleanName.isNotEmpty()) cleanName else "PULSAR"
    }

    /**
     * Clean-room translation of the 20-character SoftAP WPA2 passphrase generator
     * seeded from `(vin + salt)` into a zero-padded 50-byte buffer.
     */
    fun deriveSoftApPassword(vin: String): String {
        val rawBytes = (vin.trim() + TftWifiProtocol.PSK_SALT).toByteArray(StandardCharsets.UTF_8)
        val input = ByteArray(TftWifiProtocol.PSK_INPUT_BUFFER_BYTES)
        val copyLen = min(rawBytes.size, input.size)
        System.arraycopy(rawBytes, 0, input, 0, copyLen)

        val output = ByteArray(TftWifiProtocol.PSK_OUTPUT_LENGTH)
        scramble(input, TftWifiProtocol.PSK_INPUT_BUFFER_BYTES, output, TftWifiProtocol.PSK_OUTPUT_LENGTH)
        return String(output, StandardCharsets.US_ASCII)
    }

    internal fun scramble(input: ByteArray, inputLen: Int, output: ByteArray, outputLen: Int) {
        val alphabet = TftWifiProtocol.PSK_ALPHABET.toCharArray()
        val alphabetLen = alphabet.size

        var stateA = 0
        var stateB = 0
        for (i in 0 until inputLen) {
            val fwd = input[i].toInt() and 0xFF
            val s1 = i % 8
            val rotFwd = ((fwd shl s1) and 0xFF) or ((fwd ushr (8 - s1)) and 0xFF)
            stateA = (((stateA * 31) + fwd) xor rotFwd) and 0xFF

            val rev = input[inputLen - 1 - i].toInt() and 0xFF
            val s2 = i % 7
            val rotRev = ((rev shl s2) and 0xFF) or ((rev ushr (7 - s2)) and 0xFF)
            stateB = (((stateB * 37) + rev) xor rotRev) and 0xFF
        }

        for (j in 0 until outputLen) {
            var mix = (input[j % inputLen].toInt() and 0xFF) xor ((j * 157) and 0xFF)
            for (k in 0 until 8) {
                val tweak = ((stateA + mix) + (k * 173)) and 0xFF
                stateB = (stateB xor tweak) and 0xFF
                val mul = (mix * (k + 1)) and 0xFF
                stateA = (stateA xor mul) and 0xFF
                val idx = (j + k) % inputLen
                val inVal = input[idx].toInt() and 0xFF
                mix = (mix + inVal + k + stateB) and 0xFF
                val tmp = stateB
                stateB = stateA
                stateA = tmp
            }
            val ch = alphabet[(stateA xor stateB) % alphabetLen]
            val ascii = ch.code.toByte().toInt()
            output[j] = ascii.toByte()
            stateA = stateA xor ((ascii * 199) and 0xFF)
            stateB = stateB xor ((ascii * 223) and 0xFF)
        }
    }
}

data class TftDebugFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val quality: Int,
    val byteSize: Int,
    val frameIndex: Long,
    val timestampMs: Long,
    val isLiveWifi: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TftDebugFrame) return false
        return frameIndex == other.frameIndex &&
            byteSize == other.byteSize &&
            quality == other.quality &&
            isLiveWifi == other.isLiveWifi
    }

    override fun hashCode(): Int {
        var result = frameIndex.hashCode()
        result = 31 * result + byteSize
        result = 31 * result + quality
        return result
    }
}

data class TftTcpFrame(
    val type: Int,
    val group: Int,
    val cmd: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TftTcpFrame) return false
        return type == other.type && group == other.group && cmd == other.cmd && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + group
        result = 31 * result + cmd
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

sealed class ClusterTcpRequest {
    data class Touch(val touchType: Int, val x: Int, val y: Int) : ClusterTcpRequest()
    data class HandlebarInput(val rawBits: Int, val recenterRequested: Boolean) : ClusterTcpRequest()
    data class BikeInfo(val isStationary: Boolean) : ClusterTcpRequest()
    object ScreenInfoRequest : ClusterTcpRequest()
    data class ScreenControl(val rawMode: Int) : ClusterTcpRequest()
    data class DocDataRequest(val docNum: Int) : ClusterTcpRequest()
    data class DocSizeRequest(val docNum: Int) : ClusterTcpRequest()
    data class DocMd5Request(val docNum: Int) : ClusterTcpRequest()
    object DateTimeRequest : ClusterTcpRequest()
    data class MirroringStateChanged(val active: Boolean) : ClusterTcpRequest()
    object PhoneNameRequest : ClusterTcpRequest()
    object StopNavigation : ClusterTcpRequest()
    data class NavV2Command(val cmd: Int, val payload: ByteArray) : ClusterTcpRequest() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NavV2Command) return false
            return cmd == other.cmd && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
    }

    data class Unknown(val group: Int, val cmd: Int, val payload: ByteArray) : ClusterTcpRequest() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return group == other.group && cmd == other.cmd && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = group
            result = 31 * result + cmd
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }
}

/**
 * Codec and parser for the 5-byte-header TCP 5558 control channel between phone and TFT cluster.
 */
object TftTcpCodec {

    data class DecodeResult(
        val frame: TftTcpFrame?,
        val consumedBytes: Int,
        val needMoreData: Boolean
    )

    fun decodeNextFrame(buffer: ByteArray, length: Int): DecodeResult {
        if (length < TftWifiProtocol.TCP_HEADER_SIZE) {
            return DecodeResult(frame = null, consumedBytes = 0, needMoreData = true)
        }
        val frameType = buffer[0].toInt() and 0xFF
        val group = buffer[1].toInt() and 0xFF
        val cmd = buffer[2].toInt() and 0xFF
        val payloadLen = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)

        if (frameType != TftWifiProtocol.TYPE_CLUSTER_REQUEST || payloadLen > TftWifiProtocol.MAX_CLUSTER_REQUEST_PAYLOAD) {
            return DecodeResult(frame = null, consumedBytes = 1, needMoreData = false)
        }

        val totalBytes = TftWifiProtocol.TCP_HEADER_SIZE + payloadLen
        if (length < totalBytes) {
            return DecodeResult(frame = null, consumedBytes = 0, needMoreData = true)
        }

        val payload = buffer.copyOfRange(TftWifiProtocol.TCP_HEADER_SIZE, totalBytes)
        return DecodeResult(
            frame = TftTcpFrame(frameType, group, cmd, payload),
            consumedBytes = totalBytes,
            needMoreData = false
        )
    }

    fun parseClusterRequest(frame: TftTcpFrame): ClusterTcpRequest {
        val p = frame.payload
        return when (frame.group) {
            TftWifiProtocol.GROUP_INPUT -> when (frame.cmd) {
                TftWifiProtocol.CMD_TOUCH -> {
                    if (p.size >= 7) {
                        val touchType = p[0].toInt() and 0xFF
                        val x = ((p[3].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF)
                        val y = ((p[5].toInt() and 0xFF) shl 8) or (p[6].toInt() and 0xFF)
                        ClusterTcpRequest.Touch(touchType, x, y)
                    } else {
                        ClusterTcpRequest.Unknown(frame.group, frame.cmd, p)
                    }
                }
                TftWifiProtocol.CMD_HANDLEBAR_INPUT -> {
                    val bits = if (p.isNotEmpty()) p[0].toInt() and 0xFF else 0
                    ClusterTcpRequest.HandlebarInput(
                        rawBits = bits,
                        recenterRequested = (bits and TftWifiProtocol.HANDLEBAR_RECENTER_MASK) != 0
                    )
                }
                else -> ClusterTcpRequest.Unknown(frame.group, frame.cmd, p)
            }
            TftWifiProtocol.GROUP_SCREEN -> when (frame.cmd) {
                TftWifiProtocol.CMD_BIKE_INFO -> {
                    val stationary = p.isNotEmpty() && (p[0].toInt() and 0xFF) == 1
                    ClusterTcpRequest.BikeInfo(isStationary = stationary)
                }
                TftWifiProtocol.CMD_SCREEN_INFO -> ClusterTcpRequest.ScreenInfoRequest
                TftWifiProtocol.CMD_SCREEN_CONTROL -> {
                    val mode = if (p.isNotEmpty()) p[0].toInt() and 0xFF else 0
                    ClusterTcpRequest.ScreenControl(mode)
                }
                else -> ClusterTcpRequest.Unknown(frame.group, frame.cmd, p)
            }
            TftWifiProtocol.GROUP_DATA -> when (frame.cmd) {
                TftWifiProtocol.CMD_DOC_DATA -> ClusterTcpRequest.DocDataRequest(
                    if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
                )
                TftWifiProtocol.CMD_DOC_SIZE -> ClusterTcpRequest.DocSizeRequest(
                    if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
                )
                TftWifiProtocol.CMD_DOC_MD5 -> ClusterTcpRequest.DocMd5Request(
                    if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
                )
                TftWifiProtocol.CMD_DATE_TIME -> ClusterTcpRequest.DateTimeRequest
                TftWifiProtocol.CMD_MIRRORING_STATE -> ClusterTcpRequest.MirroringStateChanged(
                    active = p.isNotEmpty() && (p[0].toInt() and 0xFF) == 1
                )
                TftWifiProtocol.CMD_PHONE_NAME -> ClusterTcpRequest.PhoneNameRequest
                TftWifiProtocol.CMD_STOP_NAV -> ClusterTcpRequest.StopNavigation
                in 0x07..0x0F -> ClusterTcpRequest.NavV2Command(frame.cmd, p)
                else -> ClusterTcpRequest.Unknown(frame.group, frame.cmd, p)
            }
            else -> ClusterTcpRequest.Unknown(frame.group, frame.cmd, p)
        }
    }

    fun buildScreenInfoResponse(screenMode: Int): ByteArray = byteArrayOf(
        TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte(),
        TftWifiProtocol.GROUP_SCREEN.toByte(),
        TftWifiProtocol.CMD_SCREEN_INFO.toByte(),
        0x00,
        0x01,
        screenMode.toByte()
    )

    fun buildScreenControlResponse(): ByteArray = byteArrayOf(
        TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte(),
        TftWifiProtocol.GROUP_SCREEN.toByte(),
        TftWifiProtocol.CMD_SCREEN_CONTROL.toByte(),
        0x00,
        0x01,
        0x01
    )

    fun buildDateTimeResponse(calendar: Calendar): ByteArray = buildDateTimeResponse(
        minute = calendar.get(Calendar.MINUTE),
        hour12 = calendar.get(Calendar.HOUR),
        dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH),
        month0Based = calendar.get(Calendar.MONTH),
        year = calendar.get(Calendar.YEAR)
    )

    /**
     * OEM cluster firmware expects TCP [3..4] = 0x000B (11 = total 5B header + 6B payload) on
     * CMD_DATE_TIME, rather than payload-only length 6.
     */
    fun buildDateTimeResponse(
        minute: Int,
        hour12: Int,
        dayOfMonth: Int,
        month0Based: Int,
        year: Int
    ): ByteArray = byteArrayOf(
        TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte(),
        TftWifiProtocol.GROUP_DATA.toByte(),
        TftWifiProtocol.CMD_DATE_TIME.toByte(),
        0x00,
        0x0B,
        minute.toByte(),
        hour12.toByte(),
        dayOfMonth.toByte(),
        month0Based.toByte(),
        ((year ushr 8) and 0xFF).toByte(),
        (year and 0xFF).toByte()
    )

    internal fun clipUtf8Codepoints(text: String, maxUtf8Bytes: Int): ByteArray {
        if (maxUtf8Bytes <= 0 || text.isEmpty()) return ByteArray(0)
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        if (raw.size <= maxUtf8Bytes) return raw
        var end = maxUtf8Bytes
        // Step back over any continuation bytes (10xx xxxx) so we cut on a UTF-8 codepoint boundary.
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return raw.copyOf(end)
    }

    /**
     * OEM cluster firmware expects TCP [3..4] = clipped.size + 5 on CMD_PHONE_NAME.
     */
    fun buildPhoneNameResponse(name: String, maxUtf8Bytes: Int = 24): ByteArray {
        val clipped = clipUtf8Codepoints(name.ifBlank { "PulsarNav" }, maxUtf8Bytes)
        val totalLenField = clipped.size + 5
        val out = ByteArray(5 + clipped.size)
        out[0] = TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte()
        out[1] = TftWifiProtocol.GROUP_DATA.toByte()
        out[2] = TftWifiProtocol.CMD_PHONE_NAME.toByte()
        out[3] = ((totalLenField ushr 8) and 0xFF).toByte()
        out[4] = (totalLenField and 0xFF).toByte()
        System.arraycopy(clipped, 0, out, 5, clipped.size)
        return out
    }

    fun buildDocSizeResponse(docNum: Int, docBytes: ByteArray): ByteArray {
        val encodedSize = docBytes.size + 4
        return byteArrayOf(
            TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte(),
            TftWifiProtocol.GROUP_DATA.toByte(),
            TftWifiProtocol.CMD_DOC_SIZE.toByte(),
            0x00,
            0x05,
            ((encodedSize ushr 24) and 0xFF).toByte(),
            ((encodedSize ushr 16) and 0xFF).toByte(),
            ((encodedSize ushr 8) and 0xFF).toByte(),
            (encodedSize and 0xFF).toByte(),
            docNum.toByte()
        )
    }

    fun buildDocMd5Response(docNum: Int, docBytes: ByteArray): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(docBytes)
        val out = ByteArray(22)
        out[0] = TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte()
        out[1] = TftWifiProtocol.GROUP_DATA.toByte()
        out[2] = TftWifiProtocol.CMD_DOC_MD5.toByte()
        out[3] = 0x00
        out[4] = 0x11
        out[5] = docNum.toByte()
        System.arraycopy(md5, 0, out, 6, 16)
        return out
    }

    /**
     * OEM cluster firmware expects TCP [3..4] = docBytes.size + 5 (capped at 0xFFFF sentinel
     * when > 65530) while transmitting 5B header + 4B encodedSize + 1B docNum + docBytes + 4B CRC32.
     */
    fun buildDocDataResponse(docNum: Int, docBytes: ByteArray): ByteArray {
        val crc = TftUdpFrameEncoder.computeCrc32Int(docBytes)
        val rawHeaderSize = docBytes.size + 5
        val headerSizeField = if (rawHeaderSize > 65_530) 0xFFFF else rawHeaderSize
        val encodedSize = docBytes.size + 4
        val out = ByteArray(10 + docBytes.size + 4)
        out[0] = TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte()
        out[1] = TftWifiProtocol.GROUP_DATA.toByte()
        out[2] = TftWifiProtocol.CMD_DOC_DATA.toByte()
        out[3] = ((headerSizeField ushr 8) and 0xFF).toByte()
        out[4] = (headerSizeField and 0xFF).toByte()
        out[5] = ((encodedSize ushr 24) and 0xFF).toByte()
        out[6] = ((encodedSize ushr 16) and 0xFF).toByte()
        out[7] = ((encodedSize ushr 8) and 0xFF).toByte()
        out[8] = (encodedSize and 0xFF).toByte()
        out[9] = docNum.toByte()
        System.arraycopy(docBytes, 0, out, 10, docBytes.size)
        val crcPos = 10 + docBytes.size
        out[crcPos] = ((crc ushr 24) and 0xFF).toByte()
        out[crcPos + 1] = ((crc ushr 16) and 0xFF).toByte()
        out[crcPos + 2] = ((crc ushr 8) and 0xFF).toByte()
        out[crcPos + 3] = (crc and 0xFF).toByte()
        return out
    }

    fun buildStopNavigationFrame(): ByteArray = byteArrayOf(
        TftWifiProtocol.TYPE_PHONE_PUSH.toByte(),
        TftWifiProtocol.GROUP_DATA.toByte(),
        TftWifiProtocol.CMD_STOP_NAV.toByte(),
        0x00,
        0x00
    )

    fun buildShowPopupFrame(): ByteArray = byteArrayOf(
        TftWifiProtocol.TYPE_PHONE_PUSH.toByte(),
        TftWifiProtocol.GROUP_POPUP.toByte(),
        TftWifiProtocol.CMD_SHOW_POPUP.toByte(),
        0x00,
        0x00
    )

    fun buildNavV2AckResponse(cmd: Int, statusByte: Int = 0x00): ByteArray = byteArrayOf(
        TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte(),
        TftWifiProtocol.GROUP_DATA.toByte(),
        cmd.toByte(),
        0x00,
        0x01,
        statusByte.toByte()
    )
}

enum class NavV2Source(val value: Int) {
    POI(0),
    FAVOURITE(1),
    RECENT(2);

    companion object {
        fun from(value: Int): NavV2Source = values().firstOrNull { it.value == value } ?: POI
    }
}

enum class NavV2RouteMode(val value: Int) {
    FASTEST(0),
    SHORTEST(1),
    ECO(2),
    AVOID_TOLLS(3);

    companion object {
        fun fromOrNull(value: Int): NavV2RouteMode? = values().firstOrNull { it.value == value }
        fun from(value: Int): NavV2RouteMode = fromOrNull(value) ?: FASTEST
    }
}

enum class NavV2Units(val value: Int) {
    KILOMETRES(0),
    MILES(1);

    companion object {
        fun fromOrNull(value: Int): NavV2Units? = values().firstOrNull { it.value == value }
        fun from(value: Int): NavV2Units = fromOrNull(value) ?: KILOMETRES
    }
}

enum class NavV2AckStatus(val value: Int) {
    SUCCESS(0),
    FAILURE(1),
    INVALID_INDEX(2)
}

enum class NavV2NavStatus(val value: Int) {
    STARTED(0),
    FAILED(1),
    REROUTING(2)
}

data class NavV2IndexedPlace(
    val index: Int,
    val name: String,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String = "",
    val distanceText: String = "",
    val etaText: String = ""
) {
    fun hasCoordinates(): Boolean = latitude != 0.0 || longitude != 0.0
}

data class NavV2RoutePreference(
    val bitmask: Int = 0,
    val routeMode: NavV2RouteMode = NavV2RouteMode.FASTEST,
    val units: NavV2Units = NavV2Units.KILOMETRES
) {
    fun payloadBytes(): ByteArray = byteArrayOf(
        (bitmask and 0x0F).toByte(),
        routeMode.value.toByte(),
        units.value.toByte(),
        0x00
    )
}

/**
 * Pure-Kotlin request/response codec for the TFT NAV_V2 protocol (TCP 5558, Group 0x02, cmds 0x07..0x0F).
 */
object TftNavV2Codec {

    fun buildResponse(cmd: Int, payload: ByteArray?): ByteArray {
        val body = payload ?: ByteArray(0)
        val out = ByteArray(5 + body.size)
        out[0] = TftWifiProtocol.TYPE_PHONE_RESPONSE.toByte()
        out[1] = TftWifiProtocol.GROUP_DATA.toByte()
        out[2] = (cmd and 0xFF).toByte()
        out[3] = ((body.size ushr 8) and 0xFF).toByte()
        out[4] = (body.size and 0xFF).toByte()
        if (body.isNotEmpty()) {
            System.arraycopy(body, 0, out, 5, body.size)
        }
        return out
    }

    fun encodeIndexedNameList(places: List<NavV2IndexedPlace>): ByteArray {
        val out = ArrayList<Byte>(64)
        val count = places.size.coerceAtMost(5)
        out.add(count.toByte())
        for (i in 0 until count) {
            val place = places[i]
            out.add((place.index and 0xFF).toByte())
            appendLengthPrefixedUtf8(place.name, out)
        }
        return out.toByteArray()
    }

    fun encodeDetail(
        index: Int,
        name: String,
        distanceText: String,
        etaText: String
    ): ByteArray {
        val out = ArrayList<Byte>(64)
        out.add((index and 0xFF).toByte())
        appendLengthPrefixedUtf8(name, out)
        appendLengthPrefixedUtf8(distanceText, out)
        appendLengthPrefixedUtf8(etaText, out)
        return out.toByteArray()
    }

    fun encodeRoutePreview(
        source: NavV2Source,
        index: Int,
        routeMode: NavV2RouteMode,
        distanceText: String,
        etaText: String
    ): ByteArray {
        val out = ArrayList<Byte>(48)
        out.add(source.value.toByte())
        out.add((index and 0xFF).toByte())
        out.add(routeMode.value.toByte())
        appendLengthPrefixedUtf8(distanceText, out)
        appendLengthPrefixedUtf8(etaText, out)
        return out.toByteArray()
    }

    fun encodePreference(pref: NavV2RoutePreference): ByteArray = pref.payloadBytes()

    fun encodePreferenceAck(status: NavV2AckStatus): ByteArray = byteArrayOf(status.value.toByte())

    fun encodeNavStatus(status: NavV2NavStatus, message: String): ByteArray {
        val out = ArrayList<Byte>(32)
        out.add(status.value.toByte())
        appendLengthPrefixedUtf8(message, out)
        return out.toByteArray()
    }

    fun parseSetRoutePreference(payload: ByteArray): NavV2RoutePreference? {
        if (payload.size < 4) return null
        if ((payload[3].toInt() and 0xFF) != 0) return null
        val routeMode = NavV2RouteMode.fromOrNull(payload[1].toInt() and 0xFF) ?: return null
        val units = NavV2Units.fromOrNull(payload[2].toInt() and 0xFF) ?: return null
        val bitmask = payload[0].toInt() and 0x0F
        return NavV2RoutePreference(bitmask = bitmask, routeMode = routeMode, units = units)
    }

    fun formatDistanceText(meters: Double, units: NavV2Units): String {
        val clamped = meters.coerceAtLeast(0.0)
        return when (units) {
            NavV2Units.MILES -> {
                val miles = clamped / 1609.344
                if (miles < 0.2) {
                    val feet = (clamped * 3.28084).toInt().coerceAtLeast(10)
                    "$feet ft"
                } else {
                    String.format(java.util.Locale.US, "%.1f mi", miles)
                }
            }
            NavV2Units.KILOMETRES -> {
                if (clamped < 1000.0) {
                    "${clamped.toInt()} m"
                } else {
                    String.format(java.util.Locale.US, "%.1f km", clamped / 1000.0)
                }
            }
        }
    }

    fun formatEtaText(durationMinutes: Int): String {
        val mins = durationMinutes.coerceAtLeast(1)
        val hours = mins / 60
        val rem = mins % 60
        return when {
            hours > 0 && rem > 0 -> "${hours}h ${rem}m"
            hours > 0 -> "${hours}h"
            else -> "$mins min"
        }
    }

    private fun appendLengthPrefixedUtf8(value: String, out: ArrayList<Byte>) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val len = bytes.size.coerceAtMost(255)
        out.add(len.toByte())
        for (i in 0 until len) {
            out.add(bytes[i])
        }
    }
}

/**
 * UDP 5556 encrypted frame chunker and adaptive JPEG quality regulator.
 */
object TftUdpFrameEncoder {

    private val zeroIv = IvParameterSpec(ByteArray(16))

    fun computeCrc32Int(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    fun createEncryptCipher(keyBytes: ByteArray = TftWifiProtocol.DEFAULT_AES_KEY_BYTES): Cipher {
        val cipher = Cipher.getInstance(TftWifiProtocol.AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), zeroIv)
        return cipher
    }

    fun createDecryptCipher(keyBytes: ByteArray = TftWifiProtocol.DEFAULT_AES_KEY_BYTES): Cipher {
        val cipher = Cipher.getInstance(TftWifiProtocol.AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), zeroIv)
        return cipher
    }

    /**
     * Splits a compressed JPEG (< 30,000 bytes) into AES-CBC-encrypted <=1024-byte UDP packets.
     */
    fun encodeJpegFrameChunks(
        jpeg: ByteArray,
        cipher: Cipher = createEncryptCipher()
    ): List<ByteArray> {
        if (jpeg.isEmpty() || jpeg.size >= TftWifiProtocol.UDP_MAX_JPEG_BYTES) {
            return emptyList()
        }

        val totalLen = jpeg.size
        val crc = computeCrc32Int(jpeg)
        val packets = ArrayList<ByteArray>((totalLen / TftWifiProtocol.UDP_NEXT_CHUNK_BYTES) + 2)

        if (totalLen <= TftWifiProtocol.UDP_FIRST_CHUNK_BYTES) {
            // Single self-contained frame containing 4B BE length + JPEG + 4B BE CRC32
            val plain = ByteArray(4 + totalLen + 4)
            writeIntBE(plain, 0, totalLen)
            System.arraycopy(jpeg, 0, plain, 4, totalLen)
            writeIntBE(plain, 4 + totalLen, crc)
            packets.add(cipher.doFinal(plain))
            return packets
        }

        // Chunk 0: 4-byte BE total length + first 1006 bytes (1010 plaintext -> 1024 encrypted).
        // Middle full chunks encrypt directly from [jpeg] without copyOfRange allocations.
        val scratchPlain = ByteArray(max(4 + TftWifiProtocol.UDP_FIRST_CHUNK_BYTES, TftWifiProtocol.UDP_NEXT_CHUNK_BYTES + 4))
        writeIntBE(scratchPlain, 0, totalLen)
        System.arraycopy(jpeg, 0, scratchPlain, 4, TftWifiProtocol.UDP_FIRST_CHUNK_BYTES)
        packets.add(cipher.doFinal(scratchPlain, 0, 4 + TftWifiProtocol.UDP_FIRST_CHUNK_BYTES))

        var offset = TftWifiProtocol.UDP_FIRST_CHUNK_BYTES
        while (offset < totalLen) {
            if (offset + TftWifiProtocol.UDP_NEXT_CHUNK_BYTES < totalLen) {
                packets.add(cipher.doFinal(jpeg, offset, TftWifiProtocol.UDP_NEXT_CHUNK_BYTES))
                offset += TftWifiProtocol.UDP_NEXT_CHUNK_BYTES
            } else {
                val remaining = totalLen - offset
                System.arraycopy(jpeg, offset, scratchPlain, 0, remaining)
                writeIntBE(scratchPlain, remaining, crc)
                packets.add(cipher.doFinal(scratchPlain, 0, remaining + 4))
                break
            }
        }
        return packets
    }

    fun selectNextQuality(currentQuality: Int, encodedSize: Int): Int =
        if (encodedSize > TftWifiProtocol.UDP_TARGET_JPEG_BYTES) {
            max(TftWifiProtocol.UDP_MIN_JPEG_QUALITY, currentQuality - 1)
        } else {
            min(100, currentQuality + 1)
        }

    fun computeRetryQuality(currentQuality: Int, encodedSize: Int): Int {
        if (encodedSize <= 0) return TftWifiProtocol.UDP_MIN_JPEG_QUALITY
        val proportional = floor(
            currentQuality.toDouble() * TftWifiProtocol.UDP_TARGET_JPEG_BYTES / encodedSize.toDouble()
        ).toInt()
        return max(TftWifiProtocol.UDP_MIN_JPEG_QUALITY, min(proportional, currentQuality - 5))
    }

    private fun writeIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}

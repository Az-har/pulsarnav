package com.pulsarnav.app.debug

import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.protocol.OtcPacketBuilder
import com.pulsarnav.app.wifi.TftTcpFrame
import com.pulsarnav.app.wifi.TftWifiProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class DebugCategory(val badge: String) {
    BLE("BLE"),
    WIFI("WI-FI"),
    NAV_V2("NAV_V2"),
    TELEPHONY("CALL/SMS"),
    MAPS("MAPS"),
    ERROR("ERR")
}

enum class DebugFilter(val label: String) {
    ALL("All"),
    BLE("BLE"),
    WIFI_NAV_V2("Wi-Fi / NAV_V2"),
    TELEPHONY("Calls & SMS"),
    MAPS("Maps"),
    ERRORS("Errors")
}

data class DebugLogEntry(
    val id: Long,
    val timestampMs: Long,
    val category: DebugCategory,
    val tag: String,
    val summary: String,
    val detail: String = ""
)

/**
 * Thread-safe bounded ring buffer and pure-Kotlin protocol decoder for PulsarNav's
 * live Debug Console and Packet Inspector.
 *
 * Intentionally free of Android framework dependencies so formatting, filtering, PII
 * redaction, and OTC BLE / TFT TCP frame decoding run deterministically under JVM tests.
 */
object DebugLogRepository {

    const val MAX_ENTRIES = 350
    private const val MAX_RATE_LIMIT_KEYS = 256
    private const val RATE_LIMIT_STALE_MS = 60_000L

    private val lock = Any()
    private val sequence = AtomicLong(0L)
    private val buffer = ArrayDeque<DebugLogEntry>(MAX_ENTRIES)
    private val rateLimitMap = HashMap<String, Long>()
    private var cachedErrorCount = 0
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    private val timeFormatLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private val exportTimeFormatLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    private val _entries = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val entries: StateFlow<List<DebugLogEntry>> = _entries.asStateFlow()

    private val _totalCount = MutableStateFlow(0L)
    val totalCount: StateFlow<Long> = _totalCount.asStateFlow()

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    private fun appendLog(
        category: DebugCategory,
        tag: String,
        summary: String,
        detail: String,
        timestampMs: Long,
        redactDetail: Boolean
    ) {
        val cleanTag = tag.trim().ifEmpty { "App" }
        val cleanSummary = redactSensitiveText(summary.trim())
        val rawDetail = detail.trim()
        val cleanDetail = if (redactDetail) redactSensitiveText(rawDetail) else rawDetail
        synchronized(lock) {
            val entry = DebugLogEntry(
                id = sequence.incrementAndGet(),
                timestampMs = timestampMs,
                category = category,
                tag = cleanTag,
                summary = cleanSummary,
                detail = cleanDetail
            )
            if (buffer.size >= MAX_ENTRIES) {
                val evicted = buffer.removeFirst()
                if (evicted.category == DebugCategory.ERROR && cachedErrorCount > 0) {
                    cachedErrorCount--
                }
            }
            buffer.addLast(entry)
            if (entry.category == DebugCategory.ERROR) {
                cachedErrorCount++
            }
            val snapshot = buffer.toList()
            _entries.value = snapshot
            _totalCount.value = entry.id
            _errorCount.value = cachedErrorCount
        }
    }

    fun log(
        category: DebugCategory,
        tag: String,
        summary: String,
        detail: String = "",
        timestampMs: Long = System.currentTimeMillis()
    ) = appendLog(
        category = category,
        tag = tag,
        summary = summary,
        detail = detail,
        timestampMs = timestampMs,
        redactDetail = true
    )

    /**
     * Logs an entry only if [key] has not been logged within [minIntervalMs].
     * Prevents periodic heartbeats or 500ms TBT frames from drowning user events.
     */
    fun logRateLimited(
        key: String,
        minIntervalMs: Long,
        category: DebugCategory,
        tag: String,
        summary: String,
        detail: String = "",
        timestampMs: Long = System.currentTimeMillis()
    ) = logRateLimitedInternal(key, minIntervalMs, category, tag, summary, detail, timestampMs, redactDetail = true)

    private fun logRateLimitedInternal(
        key: String,
        minIntervalMs: Long,
        category: DebugCategory,
        tag: String,
        summary: String,
        detail: String,
        timestampMs: Long,
        redactDetail: Boolean
    ) {
        synchronized(lock) {
            val lastMs = rateLimitMap[key] ?: Long.MIN_VALUE
            if (lastMs != Long.MIN_VALUE && (timestampMs - lastMs) < minIntervalMs) {
                return
            }
            if (rateLimitMap.size >= MAX_RATE_LIMIT_KEYS) {
                val iter = rateLimitMap.entries.iterator()
                while (iter.hasNext()) {
                    val existing = iter.next()
                    if (timestampMs - existing.value > RATE_LIMIT_STALE_MS) {
                        iter.remove()
                    }
                }
                if (rateLimitMap.size >= MAX_RATE_LIMIT_KEYS) {
                    val oldestKey = rateLimitMap.minByOrNull { it.value }?.key
                    if (oldestKey != null) {
                        rateLimitMap.remove(oldestKey)
                    }
                }
            }
            rateLimitMap[key] = timestampMs
        }
        appendLog(category, tag, summary, detail, timestampMs, redactDetail = redactDetail)
    }

    private fun hexHeaderBytesForUuid(uuidShort: String): Int {
        return when (uuidShort.lowercase(Locale.US)) {
            "0110" -> 14 // Before [14..45] street name
            "0210" -> 18 // Before [19..51] caller name
            "0310", "0410", "0910", "0b10" -> 2 // Exclude name/phone payload bytes
            else -> 20
        }
    }

    fun bleTx(uuidShort: String, bytes: ByteArray, rateLimitKey: String? = null, minIntervalMs: Long = 0L) {
        val decoded = summarizeBlePacket(bytes, uuidShort)
        val hex = toCompactHex(bytes, maxBytes = hexHeaderBytesForUuid(uuidShort))
        if (rateLimitKey != null && minIntervalMs > 0L) {
            logRateLimitedInternal(rateLimitKey, minIntervalMs, DebugCategory.BLE, "BLE_TX/$uuidShort", decoded, hex, System.currentTimeMillis(), redactDetail = false)
        } else {
            appendLog(DebugCategory.BLE, "BLE_TX/$uuidShort", decoded, hex, System.currentTimeMillis(), redactDetail = false)
        }
    }

    fun bleRx(uuidShort: String, bytes: ByteArray) {
        val decoded = summarizeBlePacket(bytes, uuidShort)
        val hex = toCompactHex(bytes, maxBytes = hexHeaderBytesForUuid(uuidShort))
        appendLog(DebugCategory.BLE, "BLE_RX/$uuidShort", decoded, hex, System.currentTimeMillis(), redactDetail = false)
    }

    fun bleEvent(summary: String, detail: String = "") {
        log(DebugCategory.BLE, "BLE", summary, detail)
    }

    fun wifi(tag: String, summary: String, detail: String = "") {
        log(DebugCategory.WIFI, tag, summary, detail)
    }

    fun navV2(tag: String, summary: String, detail: String = "") {
        log(DebugCategory.NAV_V2, tag, summary, detail)
    }

    fun telephony(tag: String, summary: String, detail: String = "") {
        log(DebugCategory.TELEPHONY, tag, summary, detail)
    }

    fun maps(tag: String, summary: String, detail: String = "") {
        log(DebugCategory.MAPS, tag, summary, detail)
    }

    fun error(tag: String, summary: String, detail: String = "") {
        log(DebugCategory.ERROR, tag, summary, detail)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            rateLimitMap.clear()
            cachedErrorCount = 0
            sequence.set(0L)
            _entries.value = emptyList()
            _totalCount.value = 0L
            _errorCount.value = 0
        }
    }

    fun filterEntries(
        entries: List<DebugLogEntry>,
        filter: DebugFilter,
        query: String = ""
    ): List<DebugLogEntry> {
        val byCategory = when (filter) {
            DebugFilter.ALL -> entries
            DebugFilter.BLE -> entries.filter { it.category == DebugCategory.BLE }
            DebugFilter.WIFI_NAV_V2 -> entries.filter {
                it.category == DebugCategory.WIFI || it.category == DebugCategory.NAV_V2
            }
            DebugFilter.TELEPHONY -> entries.filter { it.category == DebugCategory.TELEPHONY }
            DebugFilter.MAPS -> entries.filter { it.category == DebugCategory.MAPS }
            DebugFilter.ERRORS -> entries.filter { it.category == DebugCategory.ERROR }
        }
        val q = query.trim()
        if (q.isEmpty()) return byCategory
        return byCategory.filter { entry ->
            entry.tag.contains(q, ignoreCase = true) ||
                entry.summary.contains(q, ignoreCase = true) ||
                entry.detail.contains(q, ignoreCase = true)
        }
    }

    fun formatEntryLine(entry: DebugLogEntry): String {
        val ts = timeFormatLocal.get()?.format(Date(entry.timestampMs)) ?: "--:--:--.---"
        val base = "$ts [${entry.category.badge}] ${entry.tag}: ${entry.summary}"
        return if (entry.detail.isNotEmpty()) "$base | ${entry.detail}" else base
    }

    fun formatLogsForExport(
        entries: List<DebugLogEntry>,
        headerInfo: String = ""
    ): String {
        val sb = StringBuilder()
        val now = exportTimeFormatLocal.get()?.format(Date()) ?: ""
        sb.appendLine("=== PulsarNav Diagnostic Log ($now) ===")
        if (headerInfo.isNotBlank()) {
            sb.appendLine(headerInfo.trim())
        }
        sb.appendLine("Entries: ${entries.size}")
        sb.appendLine("----------------------------------------")
        for (entry in entries) {
            sb.appendLine(formatEntryLine(entry))
        }
        return sb.toString()
    }

    fun maskPhoneNumber(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return "<empty>"
        val digits = clean.filter { it.isDigit() }
        if (digits.length <= 2) return "***"
        val tail = digits.takeLast(minOf(4, digits.length - 1))
        return "***$tail"
    }

    fun maskPersonName(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return "<empty>"
        if (clean.length <= 1) return "*"
        if (clean.length == 2) return "${clean.first()}*"
        return "${clean.first()}***${clean.last()}"
    }

    fun redactSensitiveText(input: String): String {
        if (input.isEmpty()) return input
        var digitCount = 0
        for (i in 0 until input.length) {
            if (input[i] in '0'..'9' && ++digitCount >= 7) {
                break
            }
        }
        if (digitCount < 7) return input
        // Mask phone-like digit runs (>= 7 digits, including international >14-digit & dot/hyphen separated numbers)
        return PHONE_REDACT_REGEX.replace(input) { match ->
            val digits = match.value.filter { it.isDigit() }
            if (digits.length >= 7) {
                "***${digits.takeLast(4)}"
            } else {
                match.value
            }
        }
    }

    fun toCompactHex(bytes: ByteArray, maxBytes: Int = 24): String {
        if (bytes.isEmpty()) return "0B"
        val limit = minOf(bytes.size, maxBytes).coerceAtLeast(0)
        val sb = StringBuilder(limit * 3 + 16)
        for (i in 0 until limit) {
            if (i > 0) sb.append(' ')
            val b = bytes[i].toInt() and 0xFF
            sb.append(HEX_CHARS[b ushr 4])
            sb.append(HEX_CHARS[b and 0x0F])
        }
        if (bytes.size > maxBytes) {
            if (limit > 0) sb.append(' ')
            sb.append("... (").append(bytes.size).append("B)")
        } else {
            sb.append(" (").append(bytes.size).append("B)")
        }
        return sb.toString()
    }

    /**
     * Decodes any PulsarNav / Bajaj OTC BLE characteristic frame into a concise human summary.
     */
    fun summarizeBlePacket(bytes: ByteArray, uuidShort: String? = null): String {
        val tag = uuidShort?.lowercase(Locale.US) ?: inferCharacteristicShortId(bytes)
        return when (tag) {
            "0110" -> summarizeTbtPacket(bytes)
            "0210" -> summarizeGeneralPacket(bytes)
            "0310" -> summarizeMissedCallPacket(bytes)
            "0410" -> summarizeAlertPacket(bytes)
            "0510" -> summarizePlaylistPacket(bytes)
            "0610" -> summarizeMediaPacket(bytes)
            "0910" -> summarizePhonebookPacket("FavContacts", bytes)
            "0b10" -> summarizePhonebookPacket("RecentCalls", bytes)
            "0a10" -> summarizeControlPacket(bytes)
            else -> "Unknown frame (${bytes.size}B)"
        }
    }

    fun inferCharacteristicShortId(bytes: ByteArray): String = when (bytes.size) {
        OtcPacketBuilder.TBT_PACKET_SIZE -> "0110"
        OtcPacketBuilder.GENERAL_PACKET_SIZE, OtcPacketBuilder.GENERAL_LEGACY_SIZE -> "0210"
        OtcPacketBuilder.MISSED_CALL_PACKET_SIZE -> "0310"
        OtcPacketBuilder.ALERT_PACKET_SIZE, 40 -> "0410"
        OtcPacketBuilder.PLAYLIST_PACKET_SIZE -> "0510"
        OtcPacketBuilder.MUSIC_INFO_PACKET_SIZE -> "0610"
        OtcPacketBuilder.FAV_CONTACTS_PACKET_SIZE -> {
            if ((bytes[0].toInt() and 0xFF) == 0x80) "0b10" else "0910"
        }
        in 18..26 -> "0a10"
        else -> "unknown"
    }

    fun tcpCmdLabel(group: Int, cmd: Int): String = when (group) {
        TftWifiProtocol.GROUP_INPUT -> when (cmd) {
            TftWifiProtocol.CMD_TOUCH -> "TOUCH"
            TftWifiProtocol.CMD_HANDLEBAR_INPUT -> "HANDLEBAR_INPUT"
            else -> "INPUT_0x${cmd.toString(16)}"
        }
        TftWifiProtocol.GROUP_SCREEN -> when (cmd) {
            TftWifiProtocol.CMD_BIKE_INFO -> "BIKE_INFO"
            TftWifiProtocol.CMD_SCREEN_INFO -> "SCREEN_INFO"
            TftWifiProtocol.CMD_SCREEN_CONTROL -> "SCREEN_CONTROL"
            else -> "SCREEN_0x${cmd.toString(16)}"
        }
        TftWifiProtocol.GROUP_DATA -> when (cmd) {
            TftWifiProtocol.CMD_DOC_DATA -> "DOC_DATA"
            TftWifiProtocol.CMD_DOC_SIZE -> "DOC_SIZE"
            TftWifiProtocol.CMD_DOC_MD5 -> "DOC_MD5"
            TftWifiProtocol.CMD_DATE_TIME -> "DATE_TIME"
            TftWifiProtocol.CMD_MIRRORING_STATE -> "MIRRORING_STATE"
            TftWifiProtocol.CMD_PHONE_NAME -> "PHONE_NAME"
            TftWifiProtocol.CMD_STOP_NAV -> "STOP_NAV"
            TftWifiProtocol.CMD_NAV_V2_GET_POI_LIST -> "NAV_V2_GET_POI_LIST"
            TftWifiProtocol.CMD_NAV_V2_GET_POI_DETAIL -> "NAV_V2_GET_POI_DETAIL"
            TftWifiProtocol.CMD_NAV_V2_GET_FAVOURITES -> "NAV_V2_GET_FAVOURITES"
            TftWifiProtocol.CMD_NAV_V2_GET_RECENT_TRIPS -> "NAV_V2_GET_RECENT_TRIPS"
            TftWifiProtocol.CMD_NAV_V2_GET_TRIP_DETAIL -> "NAV_V2_GET_TRIP_DETAIL"
            TftWifiProtocol.CMD_NAV_V2_GET_ROUTE_PREVIEW -> "NAV_V2_GET_ROUTE_PREVIEW"
            TftWifiProtocol.CMD_NAV_V2_SET_ROUTE_PREF -> "NAV_V2_SET_ROUTE_PREF"
            TftWifiProtocol.CMD_NAV_V2_GET_ROUTE_PREF -> "NAV_V2_GET_ROUTE_PREF"
            TftWifiProtocol.CMD_NAV_V2_START_NAV -> "NAV_V2_START_NAV"
            else -> "DATA_0x${cmd.toString(16)}"
        }
        TftWifiProtocol.GROUP_POPUP -> when (cmd) {
            TftWifiProtocol.CMD_SHOW_POPUP -> "SHOW_POPUP"
            else -> "POPUP_0x${cmd.toString(16)}"
        }
        else -> "G${group}_C0x${cmd.toString(16)}"
    }

    fun summarizeTcpFrame(direction: String, frame: TftTcpFrame): String =
        "$direction ${tcpCmdLabel(frame.group, frame.cmd)} (type=${frame.type}, ${frame.payload.size}B)"

    private fun summarizeTbtPacket(b: ByteArray): String {
        if (b.size < OtcPacketBuilder.TBT_PACKET_SIZE) return "TBT malformed (${b.size}B)"
        val active = (b[0].toInt() and 0x01) != 0
        val tmhAck = b[13].toInt() and 0xFF
        if (!active) {
            return "0110 TBT_STOP (tmhAck=$tmhAck)"
        }
        val rawIcon = b[1].toInt() and 0xFF
        val blinking = rawIcon >= 98
        val baseOpcode = if (blinking) rawIcon - 32 else rawIcon
        val maneuver = Maneuver.entries.firstOrNull { it.opcode.toInt() == baseOpcode }
        val streetLen = (b[14].toInt() and 0xFF).coerceIn(0, 31)
        val street = if (streetLen > 0) {
            String(b, 15, streetLen, StandardCharsets.US_ASCII)
        } else ""
        val stepUnitM = (b[0].toInt() and 0x10) != 0
        val stepWhole = ((b[5].toInt() and 0xFF) shl 8) or (b[4].toInt() and 0xFF)
        val stepFrac = ((b[3].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        val distLabel = if (stepUnitM) {
            "${stepWhole}m"
        } else {
            String.format(Locale.US, "%d.%02dkm", stepWhole, stepFrac.coerceIn(0, 99))
        }
        val iconLabel = maneuver?.name ?: "OP_$baseOpcode"
        val blinkTag = if (blinking) "*" else ""
        return "0110 $iconLabel$blinkTag $distLabel \"${street.ifEmpty { "-" }}\""
    }

    private fun summarizeGeneralPacket(b: ByteArray): String {
        if (b.size < OtcPacketBuilder.GENERAL_LEGACY_SIZE) return "General malformed (${b.size}B)"
        val isV2 = b.size >= OtcPacketBuilder.GENERAL_PACKET_SIZE
        val callCode = b[1].toInt() and 0x0F
        val callName = CallState.entries.firstOrNull { it.value == callCode }?.name ?: "CALL_$callCode"
        val batBars = if (isV2) (b[1].toInt() ushr 4) and 0x03 else (b[1].toInt() ushr 3) and 0x07
        val vol = b[0].toInt() and 0x0F
        val sig = b[2].toInt() and 0x07
        val accAck = b[4].toInt() and 0xFF
        val rejAck = b[5].toInt() and 0xFF
        val smsAck = b[6].toInt() and 0xFF
        val variant = if (isV2) "V2" else "Legacy"
        val hasCaller = (b[18].toInt() and 0xFF) != 0
        val caller = if (hasCaller) {
            val lenOffset = if (isV2) 19 else 20
            val dataOffset = if (isV2) 20 else 21
            val maxLen = if (isV2) 32 else 30
            val callerLen = (b[lenOffset].toInt() and 0xFF).coerceIn(0, maxLen)
            if (callerLen > 0 && dataOffset + callerLen <= b.size) {
                String(b, dataOffset, callerLen, StandardCharsets.UTF_8).trim()
            } else ""
        } else ""
        val callerPart = if (caller.isNotEmpty()) " caller=\"${maskPersonName(caller)}\"" else ""
        val missedInfo = if (isV2) {
            val missed = b[13].toInt() and 0xFF
            " missed=$missed$callerPart"
        } else {
            callerPart
        }
        return "0210 $variant $callName vol=$vol/15 bat=$batBars sig=$sig ack(a=$accAck,r=$rejAck,s=$smsAck)$missedInfo"
    }

    private fun summarizeMissedCallPacket(b: ByteArray): String {
        if (b.size < OtcPacketBuilder.MISSED_CALL_PACKET_SIZE) return "0310 MissedCall (${b.size}B)"
        val nameLen = (b[1].toInt() and 0xFF).coerceIn(0, 32)
        val name = if (nameLen > 0) String(b, 2, nameLen, StandardCharsets.UTF_8) else ""
        val phoneLen = (b[34].toInt() and 0xFF).coerceIn(0, 18)
        val rawPhone = if (phoneLen > 0) String(b, 35, phoneLen, StandardCharsets.US_ASCII) else ""
        val safeName = if (name.isEmpty()) "Unknown" else maskPersonName(name)
        return "0310 MissedCall name=\"$safeName\" phone=${maskPhoneNumber(rawPhone)}"
    }

    private fun summarizeAlertPacket(b: ByteArray): String {
        if (b.size < 34) return "0410 Alert (${b.size}B)"
        val type = b[0].toInt() and 0xFF
        val nameLen = (b[1].toInt() and 0xFF).coerceIn(0, 32)
        val sender = if (nameLen > 0) String(b, 2, nameLen, StandardCharsets.UTF_8) else ""
        val safeSender = if (sender.isEmpty()) "" else maskPersonName(sender)
        return "0410 Alert type=$type sender=\"$safeSender\" (${b.size}B)"
    }

    private fun summarizePlaylistPacket(b: ByteArray): String {
        if (b.size < OtcPacketBuilder.PLAYLIST_PACKET_SIZE) return "0510 Playlist (${b.size}B)"
        val pageAck = b[0].toInt() and 0x3F
        val newAck = (b[0].toInt() ushr 6) and 0x03
        val total = b[1].toInt() and 0xFF
        val pages = b[2].toInt() and 0xFF
        val current = b[3].toInt() and 0xFF
        return "0510 Playlist page=$pageAck/$pages total=$total cur=$current newAck=$newAck"
    }

    private fun summarizeMediaPacket(b: ByteArray): String {
        if (b.size < OtcPacketBuilder.MUSIC_INFO_PACKET_SIZE) return "0610 Media (${b.size}B)"
        val titleLen = (b[1].toInt() and 0xFF).coerceIn(0, 32)
        val artistLen = (b[34].toInt() and 0xFF).coerceIn(0, 32)
        val title = if (titleLen > 0) String(b, 2, titleLen, StandardCharsets.UTF_8) else ""
        val artist = if (artistLen > 0) String(b, 35, artistLen, StandardCharsets.UTF_8) else ""
        val playStatus = when (b[104].toInt() and 0xFF) {
            2 -> "PLAY"
            1 -> "PAUSE"
            else -> "STOP"
        }
        return "0610 Media $playStatus title=\"$title\" artist=\"$artist\""
    }

    private fun summarizePhonebookPacket(label: String, b: ByteArray): String {
        if (b.size < OtcPacketBuilder.FAV_CONTACTS_PACKET_SIZE) return "$label (${b.size}B)"
        val count = ((b[1].toInt() and 0xFF) ushr 4) and 0x0F
        val charId = if ((b[0].toInt() and 0xFF) == 0x80) "0b10" else "0910"
        return "$charId $label slots=$count/5 (${b.size}B)"
    }

    private fun summarizeControlPacket(b: ByteArray): String {
        val f = OtcPacketBuilder.parseControlFrame(b) ?: return "0a10 Control (${b.size}B)"
        return "0a10 Ctl vol=${f.volumeToSet} dial(src=${f.dialSource},idx=${f.dialIndex},txn=${f.dialTxn}) " +
            "call(a=${f.callAccept},r=${f.callReject},sms=${f.callRejectWithSms}) " +
            "tmh=${f.takeMeHome} media(p=${f.resumeSong},pa=${f.pauseSong},nx=${f.skipToNext},pv=${f.skipToPrev}) " +
            "pg=${f.pagePlaylist} sel=${f.selectPlaylistSongReq}#${f.selectedPlaylistSong}"
    }

    private val PHONE_REDACT_REGEX = Regex("\\+?\\d[\\d\\s\\-.()]{6,24}\\d")
}

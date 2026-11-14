package com.pulsarnav.app.ble

/**
 * Pure-Kotlin BLE protocol and anti-brick safety predicates for Bajaj clusters.
 *
 * Extracted from [BajajBleManager] so all ATT MTU, bonding, and Long-Write guards can
 * be deterministically unit-tested on the JVM without Android framework mocks.
 */
object BleProtocolSafety {
    const val DEFAULT_ATT_MTU = 23
    const val ATT_HEADER_BYTES = 3
    const val MIN_LEGACY_WRITE_PAYLOAD = 20

    /**
     * Minimum ATT MTU before activating a TFT link: must fit the 89-byte 0210 General Status
     * heartbeat (89 + 3 = 92 bytes) in a single ATT Write Request without Long-Write fragmentation.
     * Characteristics exceeding an intermediate MTU (e.g. 111-byte 0610 Media Info requiring
     * MTU >= 114) remain individually gated by [fitsSingleAttWrite].
     */
    const val MIN_TFT_READY_MTU = 92

    /** Minimum ATT payload required for 111-byte 0610 Media Info single-PDU writes (MTU >= 114). */
    const val MEDIA_INFO_PACKET_BYTES = 111

    /** Minimum ATT payload required for 151-byte 0910/0b10 Fav Contacts & Recent Calls (MTU >= 154). */
    const val FAV_RECENT_PACKET_BYTES = 151

    /** Minimum ATT payload required for 177-byte 0510 Playlist Info single-PDU writes (MTU >= 180). */
    const val PLAYLIST_PACKET_BYTES = 177

    /**
     * Minimum ATT MTU before polling 0a10 switchgear controls (20..26 bytes + 1-byte ATT Read Rsp),
     * preventing ATT_READ_BLOB_REQ (0x0c) -> ATT Error 0x07 at default MTU 23.
     */
    const val MIN_CTL_READ_MTU = 30

    /** Mirrors BluetoothDevice.BOND_BONDING (11) & BOND_BONDED (12) without Android framework stubs. */
    const val BOND_STATE_BONDING = 11
    const val BOND_STATE_BONDED = 12

    enum class ClusterCategory {
        LCD_MONOCHROME,
        TFT_COLOR,
        AUTO_PROBE
    }

    enum class BleProtocolStrategy {
        OLD_LCD_LEGACY,
        NEW_TFT_MODERN
    }

    private val EXPLICIT_TFT_PATTERNS = listOf(
        "ns400", "ns400z", "pulsar 400", "dominar tft", "pulsar tft"
    )

    private val EXPLICIT_LCD_PATTERNS = listOf(
        "ns200", "ns160", "ns125",
        "n250", "f250", "n160", "n150", "n125",
        "pulsar 150", "pulsar 220", "pulsar 125", "220f",
        "dominar 400", "dominar 250",
        "rouser", "chetak"
    )

    fun normalizeBleName(raw: String?): String =
        raw?.substringBefore('\u0000')?.trim().orEmpty()

    /**
     * Classifies a cluster into LCD_MONOCHROME, TFT_COLOR, or AUTO_PROBE based on the bike's
     * Bluetooth name, the selected model profile, and discovered GATT characteristics.
     */
    fun classifyCluster(
        deviceName: String?,
        selectedModelId: String?,
        hasMediaInfoCharacteristic: Boolean
    ): ClusterCategory {
        if (hasMediaInfoCharacteristic) return ClusterCategory.TFT_COLOR
        val normalized = normalizeBleName(deviceName).lowercase()
        if (EXPLICIT_TFT_PATTERNS.any { normalized.contains(it) } || normalized.contains("1854") ||
            selectedModelId.equals("ns400z", ignoreCase = true)) {
            return ClusterCategory.TFT_COLOR
        }
        if (EXPLICIT_LCD_PATTERNS.any { normalized.contains(it) } ||
            (selectedModelId != null && !selectedModelId.equals("ns400z", ignoreCase = true) && selectedModelId.isNotBlank())) {
            return ClusterCategory.LCD_MONOCHROME
        }
        return ClusterCategory.AUTO_PROBE
    }

    /**
     * Identifies Bajaj TFT clusters (e.g. Pulsar NS400Z / PULSAR1854).
     * Presence of the 0610 Media Info characteristic identifies TFT hardware directly via GATT
     * even if the advertised GAP name differs from known patterns or settings default to NS200.
     */
    fun isTftCluster(
        deviceName: String?,
        selectedModelId: String?,
        hasMediaInfoCharacteristic: Boolean
    ): Boolean {
        if (hasMediaInfoCharacteristic) return true
        val normalized = normalizeBleName(deviceName).lowercase()
        if (normalized.contains("1854") || EXPLICIT_TFT_PATTERNS.any { normalized.contains(it) }) return true
        if (selectedModelId == "ns400z") return true
        if (EXPLICIT_LCD_PATTERNS.any { normalized.contains(it) }) return false
        return false
    }

    fun maxSingleWritePayload(negotiatedMtu: Int): Int =
        (negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(MIN_LEGACY_WRITE_PAYLOAD)

    /**
     * Returns the recommended ATT MTU required to use all TFT characteristics exposed by the cluster
     * (0510 playlist = 180, 0910/0b10 phonebook = 154, 0610 media = 114, 0210 general = 92).
     */
    fun recommendedTftMtu(
        hasMediaInfo: Boolean,
        hasFavRecent: Boolean,
        hasPlaylist: Boolean
    ): Int = when {
        hasPlaylist -> PLAYLIST_PACKET_BYTES + ATT_HEADER_BYTES
        hasFavRecent -> FAV_RECENT_PACKET_BYTES + ATT_HEADER_BYTES
        hasMediaInfo -> MEDIA_INFO_PACKET_BYTES + ATT_HEADER_BYTES
        else -> MIN_TFT_READY_MTU
    }

    /** GATT status codes indicating missing/expired pairing keys (5=AUTH, 15=ENC, 137=GATT_AUTH_FAIL). */
    fun isAuthOrEncryptionError(status: Int): Boolean =
        status == 5 || status == 15 || status == 137

    /**
     * Blocks multi-PDU ATT_PREPARE_WRITE_REQ (0x16) Long Writes as well as truncated
     * WRITE_TYPE_NO_RESPONSE writes whenever [packetSize] exceeds the negotiated single-PDU
     * ATT payload limit (`mtu - 3`). Matches OEM Bajaj Ride Connect's pre-write GlobalVar.isMTUIncreased gate.
     */
    fun fitsSingleAttWrite(
        isTft: Boolean,
        isWriteTypeDefault: Boolean,
        packetSize: Int,
        negotiatedMtu: Int
    ): Boolean {
        // Every ATT Write Request (ATT_PREPARE_WRITE_REQ risk) and Write Without Response
        // (silent truncation at MTU - 3 risk on legacy LCD clusters) must fit in one ATT PDU.
        return packetSize <= maxSingleWritePayload(negotiatedMtu)
    }

    fun supportsMediaInfoWrites(negotiatedMtu: Int): Boolean =
        maxSingleWritePayload(negotiatedMtu) >= MEDIA_INFO_PACKET_BYTES

    fun supportsFavRecentWrites(negotiatedMtu: Int): Boolean =
        maxSingleWritePayload(negotiatedMtu) >= FAV_RECENT_PACKET_BYTES

    fun supportsPlaylistWrites(negotiatedMtu: Int): Boolean =
        maxSingleWritePayload(negotiatedMtu) >= PLAYLIST_PACKET_BYTES

    fun canPollControlCharacteristic(isTft: Boolean, negotiatedMtu: Int): Boolean =
        !isTft || negotiatedMtu >= MIN_CTL_READ_MTU

    /**
     * Gates link activation on TFT clusters until ATT MTU negotiation succeeds and the device is
     * strictly bonded (BOND_BONDED) without pairing failure.
     */
    fun canActivateLink(
        isTft: Boolean,
        negotiatedMtu: Int,
        bondState: Int,
        bondFailedForSession: Boolean = false
    ): Boolean {
        if (!isTft) return true
        if (bondFailedForSession || bondState != BOND_STATE_BONDED) return false
        return negotiatedMtu >= MIN_TFT_READY_MTU
    }
}

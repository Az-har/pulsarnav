package com.pulsarnav.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProtocolSafetyTest {

    @Test
    fun isTftCluster_detectsByMediaCharacteristicEvenWithUnknownName() {
        assertTrue(
            BleProtocolSafety.isTftCluster(
                deviceName = "UNKNOWN_BIKE",
                selectedModelId = "ns200",
                hasMediaInfoCharacteristic = true
            )
        )
        assertTrue(
            BleProtocolSafety.isTftCluster(
                deviceName = "PULSAR1854\u0000",
                selectedModelId = "ns200",
                hasMediaInfoCharacteristic = false
            )
        )
        assertTrue(
            BleProtocolSafety.isTftCluster(
                deviceName = "Pulsar NS200",
                selectedModelId = "ns400z",
                hasMediaInfoCharacteristic = false
            )
        )
        assertFalse(
            BleProtocolSafety.isTftCluster(
                deviceName = "Pulsar NS200",
                selectedModelId = "ns200",
                hasMediaInfoCharacteristic = false
            )
        )
    }

    @Test
    fun classifyCluster_correctlyCategorizesLcdVsTftModels() {
        assertEquals(
            BleProtocolSafety.ClusterCategory.LCD_MONOCHROME,
            BleProtocolSafety.classifyCluster("Pulsar NS200", "ns200", false)
        )
        assertEquals(
            BleProtocolSafety.ClusterCategory.LCD_MONOCHROME,
            BleProtocolSafety.classifyCluster("Pulsar N250", "n250", false)
        )
        assertEquals(
            BleProtocolSafety.ClusterCategory.LCD_MONOCHROME,
            BleProtocolSafety.classifyCluster("Dominar 400", "dominar400", false)
        )
        assertEquals(
            BleProtocolSafety.ClusterCategory.TFT_COLOR,
            BleProtocolSafety.classifyCluster("Pulsar NS400Z", "ns400z", false)
        )
        assertEquals(
            BleProtocolSafety.ClusterCategory.TFT_COLOR,
            BleProtocolSafety.classifyCluster("UNKNOWN_CLUSTER", "ns200", true)
        )
        assertEquals(
            BleProtocolSafety.ClusterCategory.AUTO_PROBE,
            BleProtocolSafety.classifyCluster("GENERIC_BT", "", false)
        )
    }

    @Test
    fun fitsSingleAttWrite_blocksOversizedOrDefaultMtuLongWrites() {
        // Default ATT MTU = 23 -> max single-PDU payload = 20 bytes
        assertEquals(20, BleProtocolSafety.maxSingleWritePayload(23))
        assertFalse(BleProtocolSafety.fitsSingleAttWrite(isTft = true, isWriteTypeDefault = true, packetSize = 48, negotiatedMtu = 23))
        assertFalse(BleProtocolSafety.fitsSingleAttWrite(isTft = true, isWriteTypeDefault = true, packetSize = 89, negotiatedMtu = 23))
        assertFalse(BleProtocolSafety.fitsSingleAttWrite(isTft = true, isWriteTypeDefault = true, packetSize = 111, negotiatedMtu = 23))
        assertFalse(BleProtocolSafety.fitsSingleAttWrite(isTft = false, isWriteTypeDefault = true, packetSize = 111, negotiatedMtu = 23))

        // Intermediate MTU = 100 -> fits 89B 0210 (97B max) but blocks 111B 0610
        assertTrue(BleProtocolSafety.fitsSingleAttWrite(isTft = true, isWriteTypeDefault = true, packetSize = 89, negotiatedMtu = 100))
        assertFalse(BleProtocolSafety.fitsSingleAttWrite(isTft = true, isWriteTypeDefault = true, packetSize = 111, negotiatedMtu = 100))

        // Standard PULSAR1854 negotiated MTU = 247 -> fits all OTC packets in a single ATT Write Request
        assertTrue(BleProtocolSafety.fitsSingleAttWrite(isTft = true, isWriteTypeDefault = true, packetSize = 48, negotiatedMtu = 247))
        assertTrue(BleProtocolSafety.fitsSingleAttWrite(isTft = true, isWriteTypeDefault = true, packetSize = 89, negotiatedMtu = 247))
        assertTrue(BleProtocolSafety.fitsSingleAttWrite(isTft = true, isWriteTypeDefault = true, packetSize = 111, negotiatedMtu = 247))
    }

    @Test
    fun canPollControlCharacteristic_blocksReadAtDefaultMtuToPreventReadBlob() {
        assertFalse(BleProtocolSafety.canPollControlCharacteristic(isTft = true, negotiatedMtu = 23))
        assertTrue(BleProtocolSafety.canPollControlCharacteristic(isTft = true, negotiatedMtu = 247))
        assertTrue(BleProtocolSafety.canPollControlCharacteristic(isTft = false, negotiatedMtu = 23))
    }

    @Test
    fun canActivateLink_blocksTftWithoutSufficientMtuOrSettledBond() {
        // Requires BOND_STATE_BONDED (12): BOND_NONE (10) and BOND_BONDING (11) must both be rejected
        assertFalse(BleProtocolSafety.canActivateLink(isTft = true, negotiatedMtu = 247, bondState = 10))
        assertFalse(BleProtocolSafety.canActivateLink(isTft = true, negotiatedMtu = 23, bondState = BleProtocolSafety.BOND_STATE_BONDED))
        assertFalse(BleProtocolSafety.canActivateLink(isTft = true, negotiatedMtu = 60, bondState = BleProtocolSafety.BOND_STATE_BONDED))
        assertFalse(
            BleProtocolSafety.canActivateLink(
                isTft = true,
                negotiatedMtu = 247,
                bondState = BleProtocolSafety.BOND_STATE_BONDING
            )
        )
        assertFalse(
            BleProtocolSafety.canActivateLink(
                isTft = true,
                negotiatedMtu = 247,
                bondState = BleProtocolSafety.BOND_STATE_BONDED,
                bondFailedForSession = true
            )
        )
        assertTrue(BleProtocolSafety.canActivateLink(isTft = true, negotiatedMtu = 247, bondState = BleProtocolSafety.BOND_STATE_BONDED))
        assertTrue(BleProtocolSafety.canActivateLink(isTft = false, negotiatedMtu = 23, bondState = 10))
    }

    @Test
    fun supportsMediaInfoWrites_requiresMtu114OrHigher() {
        assertFalse(BleProtocolSafety.supportsMediaInfoWrites(92))
        assertFalse(BleProtocolSafety.supportsMediaInfoWrites(113))
        assertTrue(BleProtocolSafety.supportsMediaInfoWrites(114))
        assertTrue(BleProtocolSafety.supportsMediaInfoWrites(247))
    }

    @Test
    fun supportsFavRecentAndPlaylistWrites_enforceSinglePduMtuThresholds() {
        assertFalse(BleProtocolSafety.supportsFavRecentWrites(153))
        assertTrue(BleProtocolSafety.supportsFavRecentWrites(154))
        assertFalse(BleProtocolSafety.supportsPlaylistWrites(179))
        assertTrue(BleProtocolSafety.supportsPlaylistWrites(180))
        assertTrue(BleProtocolSafety.supportsPlaylistWrites(247))
    }
}

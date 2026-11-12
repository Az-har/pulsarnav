package com.pulsarnav.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProtocolSafetyCriticTest {

    @Test
    fun fitsSingleAttWrite_blocksOversizedWriteWithoutResponseAndWriteDefault() {
        assertFalse(
            BleProtocolSafety.fitsSingleAttWrite(
                isTft = false,
                isWriteTypeDefault = false,
                packetSize = 48,
                negotiatedMtu = 23
            )
        )
        assertTrue(
            BleProtocolSafety.fitsSingleAttWrite(
                isTft = false,
                isWriteTypeDefault = false,
                packetSize = 20,
                negotiatedMtu = 23
            )
        )
        assertTrue(
            BleProtocolSafety.fitsSingleAttWrite(
                isTft = true,
                isWriteTypeDefault = false,
                packetSize = 177,
                negotiatedMtu = 180
            )
        )
        assertFalse(
            BleProtocolSafety.fitsSingleAttWrite(
                isTft = true,
                isWriteTypeDefault = true,
                packetSize = 177,
                negotiatedMtu = 179
            )
        )
    }

    @Test
    fun recommendedTftMtu_matchesHighestSupportedCharacteristic() {
        assertEquals(180, BleProtocolSafety.recommendedTftMtu(hasMediaInfo = true, hasFavRecent = true, hasPlaylist = true))
        assertEquals(154, BleProtocolSafety.recommendedTftMtu(hasMediaInfo = true, hasFavRecent = true, hasPlaylist = false))
        assertEquals(114, BleProtocolSafety.recommendedTftMtu(hasMediaInfo = true, hasFavRecent = false, hasPlaylist = false))
        assertEquals(92, BleProtocolSafety.recommendedTftMtu(hasMediaInfo = false, hasFavRecent = false, hasPlaylist = false))
    }

    @Test
    fun isAuthOrEncryptionError_identifiesGattAuthFailuresOnly() {
        assertTrue(BleProtocolSafety.isAuthOrEncryptionError(5))
        assertTrue(BleProtocolSafety.isAuthOrEncryptionError(15))
        assertTrue(BleProtocolSafety.isAuthOrEncryptionError(137))

        assertFalse(BleProtocolSafety.isAuthOrEncryptionError(0))
        assertFalse(BleProtocolSafety.isAuthOrEncryptionError(8))
        assertFalse(BleProtocolSafety.isAuthOrEncryptionError(133))
        assertFalse(BleProtocolSafety.isAuthOrEncryptionError(257))
    }
}

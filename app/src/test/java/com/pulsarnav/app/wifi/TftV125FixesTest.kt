package com.pulsarnav.app.wifi

import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TftV125FixesTest {

    @Test
    fun computeSafeViewport_usesLeftPocketOnlyForPortraitFit() {
        val portraitFit = TftScreenCastTransform.computeSafeViewport(432, 960, AppSettings.CAST_LAYOUT_FIT)
        assertEquals(
            IntRect(
                TftWifiProtocol.NS400Z_POCKET_LEFT,
                TftWifiProtocol.NS400Z_SAFE_TOP,
                TftWifiProtocol.NS400Z_POCKET_RIGHT,
                TftWifiProtocol.NS400Z_POCKET_BOTTOM
            ),
            portraitFit
        )

        val landscapeFit = TftScreenCastTransform.computeSafeViewport(960, 432, AppSettings.CAST_LAYOUT_FIT)
        val portraitFill = TftScreenCastTransform.computeSafeViewport(432, 960, AppSettings.CAST_LAYOUT_FILL)
        val portraitRot90 = TftScreenCastTransform.computeSafeViewport(432, 960, AppSettings.CAST_LAYOUT_ROT90)
        val widePanorama = IntRect(
            TftWifiProtocol.NS400Z_SAFE_LEFT,
            TftWifiProtocol.NS400Z_SAFE_TOP,
            TftWifiProtocol.NS400Z_SAFE_RIGHT,
            TftWifiProtocol.NS400Z_SAFE_BOTTOM
        )
        assertEquals(widePanorama, landscapeFit)
        assertEquals(widePanorama, portraitFill)
        assertEquals(widePanorama, portraitRot90)
    }

    @Test
    fun computeFitDestinationRect_staysInsideNs400zSafeBounds() {
        val viewport = IntRect(
            TftWifiProtocol.NS400Z_POCKET_LEFT,
            TftWifiProtocol.NS400Z_SAFE_TOP,
            TftWifiProtocol.NS400Z_POCKET_RIGHT,
            TftWifiProtocol.NS400Z_POCKET_BOTTOM
        )
        val dst = TftScreenCastTransform.computeFitDestinationRect(
            srcW = 432,
            srcH = 960,
            targetW = TftWifiProtocol.TFT_WIDTH,
            targetH = TftWifiProtocol.TFT_HEIGHT,
            viewport = viewport
        )
        assertTrue(dst.left >= TftWifiProtocol.NS400Z_POCKET_LEFT)
        assertTrue(dst.top >= TftWifiProtocol.NS400Z_SAFE_TOP)
        assertTrue(dst.right <= TftWifiProtocol.NS400Z_POCKET_RIGHT)
        assertTrue(dst.bottom <= TftWifiProtocol.NS400Z_POCKET_BOTTOM)
    }

    @Test
    fun decodeNextFrame_acceptsPhoneResponseInLoopbackDiagnostics() {
        val frame = TftNavV2Codec.buildResponse(TftWifiProtocol.CMD_NAV_V2_GET_FAVOURITES, byteArrayOf(0x01, 0x02))

        // Default decoder strictly expects cluster requests (0x00)
        assertNull(TftTcpCodec.decodeNextFrame(frame, frame.size).frame)

        // Loopback validation explicitly requests TYPE_PHONE_RESPONSE (0x01)
        val decoded = TftTcpCodec.decodeNextFrame(
            buffer = frame,
            length = frame.size,
            expectedFrameType = TftWifiProtocol.TYPE_PHONE_RESPONSE,
            maxPayloadBytes = 16_384
        )
        assertNotNull(decoded.frame)
        assertEquals(TftWifiProtocol.TYPE_PHONE_RESPONSE, decoded.frame!!.type)
        assertEquals(TftWifiProtocol.CMD_NAV_V2_GET_FAVOURITES, decoded.frame!!.cmd)
        assertEquals(2, decoded.frame!!.payload.size)
    }

    @Test
    fun selectNextQuality_recoversFasterBelowTarget() {
        val bumped = TftUdpFrameEncoder.selectNextQuality(
            currentQuality = 25,
            encodedSize = TftWifiProtocol.UDP_TARGET_JPEG_BYTES - 5_000
        )
        assertEquals(28, bumped)

        val lowered = TftUdpFrameEncoder.selectNextQuality(
            currentQuality = 60,
            encodedSize = TftWifiProtocol.UDP_TARGET_JPEG_BYTES + 4_000
        )
        assertEquals(58, lowered)
    }

    @Test
    fun isKnownSmsPackage_matchesBuiltInAndDefaultSmsAppsOnly() {
        assertTrue(GoogleMapsNotificationListener.isKnownSmsPackage("com.google.android.apps.messaging", null))
        assertTrue(GoogleMapsNotificationListener.isKnownSmsPackage("com.vivo.message", "com.vivo.message"))
        assertFalse(GoogleMapsNotificationListener.isKnownSmsPackage("com.whatsapp", "com.google.android.apps.messaging"))
        assertFalse(GoogleMapsNotificationListener.isKnownSmsPackage(null, "com.google.android.apps.messaging"))
        assertFalse(GoogleMapsNotificationListener.isKnownSmsPackage("   ", null))
    }
}

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

    @Test
    fun udpEncoderThreadLocalBuffers_produceDeterministicChunksAcrossFrames() {
        assertEquals(4, TftWifiProtocol.UDP_TX_BURST_PACKETS)
        val cipher = TftUdpFrameEncoder.createEncryptCipher()
        val frameA = ByteArray(2_600) { (it * 7).toByte() }
        val frameB = ByteArray(1_500) { (it * 13).toByte() }

        val crcA1 = TftUdpFrameEncoder.computeCrc32Int(frameA)
        val crcB = TftUdpFrameEncoder.computeCrc32Int(frameB)
        val crcA2 = TftUdpFrameEncoder.computeCrc32Int(frameA)
        assertEquals(crcA1, crcA2)
        assertTrue(crcA1 != crcB)

        val chunksA1 = TftUdpFrameEncoder.encodeJpegFrameChunks(frameA, cipher)
        val chunksB = TftUdpFrameEncoder.encodeJpegFrameChunks(frameB, cipher)
        val chunksA2 = TftUdpFrameEncoder.encodeJpegFrameChunks(frameA, cipher)
        assertEquals(chunksA1.size, chunksA2.size)
        assertEquals(3, chunksA1.size)
        assertEquals(2, chunksB.size)
        for (i in chunksA1.indices) {
            assertTrue(chunksA1[i].contentEquals(chunksA2[i]))
        }
    }

    @Test
    fun computeSourceCropRect_landscapeAnchorToTopPreservesTurnBanner() {
        // Landscape phone 2400x1080 (aspect 2.222) cast into safe panorama 754x256 (aspect 2.945)
        val content = IntRect(0, 0, 2400, 1080)
        val crop = TftScreenCastTransform.computeSourceCropRect(
            contentRect = content,
            targetW = 754,
            targetH = 256,
            layoutMode = AppSettings.CAST_LAYOUT_FILL
        )
        // With targetAspect = 2.945, rawCropH = 2400 / 2.945 = ~815px.
        // Slack = 1080 - 815 = 265px.
        // Top offset with 0.05f bias should be ~13px (near 0, top-anchored), NEVER centered (132px).
        assertEquals(0, crop.left)
        assertEquals(2400, crop.right)
        assertTrue("Top offset ${crop.top} should be <= 20px to preserve turn header", crop.top <= 20)
        assertTrue("Bottom should cover at least 70% of screen height", crop.bottom >= 800)
    }

    @Test
    fun computeSourceCropRect_tallPortraitUsesTopBias() {
        // Tall portrait phone 1080x2400 (aspect 0.45) cast into safe panorama 754x256
        val content = IntRect(0, 0, 1080, 2400)
        val crop = TftScreenCastTransform.computeSourceCropRect(
            contentRect = content,
            targetW = 754,
            targetH = 256,
            layoutMode = AppSettings.CAST_LAYOUT_FILL
        )
        // RawCropH = 1080 / 2.945 = ~366px.
        // Slack = 2400 - 366 = 2034px.
        // Bias = 0.12f -> topOffset ~ 244px.
        assertTrue("Top offset should be biased near upper third", crop.top in 150..400)
    }

    @Test
    fun shouldSuppressAlert_debouncesRapidDuplicateAlerts() {
        val map = mutableMapOf<String, Long>()
        val sender = "RiderFriend"

        // First alert: allowed
        val suppressed1 = GoogleMapsNotificationListener.shouldSuppressAlert(
            com.pulsarnav.app.protocol.AlertType.WHATSAPP,
            sender,
            100_000L,
            map
        )
        assertFalse(suppressed1)

        // Rapid duplicate (1000ms later < 3500ms): suppressed
        val suppressed2 = GoogleMapsNotificationListener.shouldSuppressAlert(
            com.pulsarnav.app.protocol.AlertType.WHATSAPP,
            sender,
            101_000L,
            map
        )
        assertTrue(suppressed2)

        // Different sender: allowed immediately
        val suppressedOther = GoogleMapsNotificationListener.shouldSuppressAlert(
            com.pulsarnav.app.protocol.AlertType.WHATSAPP,
            "WorkBoss",
            101_500L,
            map
        )
        assertFalse(suppressedOther)

        // Same sender after window expires (> 3500ms later): allowed
        val suppressed3 = GoogleMapsNotificationListener.shouldSuppressAlert(
            com.pulsarnav.app.protocol.AlertType.WHATSAPP,
            sender,
            104_000L,
            map
        )
        assertFalse(suppressed3)
    }
}

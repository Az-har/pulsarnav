package com.pulsarnav.app.wifi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

class TftWifiProtocolTest {

    @Test
    fun `softAp password derivation is deterministic 20-char alphanumeric`() {
        val vin = "MD2B45AX4RWG01854"
        val p1 = TftPasswordGenerator.deriveSoftApPassword(vin)
        val p2 = TftPasswordGenerator.deriveSoftApPassword("  $vin  ")
        val p3 = TftPasswordGenerator.deriveSoftApPassword("MD2B45AX4RWG01855")

        assertEquals(20, p1.length)
        assertEquals(p1, p2)
        assertNotEquals(p1, p3)
        assertTrue(p1.all { it in TftWifiProtocol.PSK_ALPHABET })
    }

    @Test
    fun `default SSID prefers PULSAR ble name then VIN suffix`() {
        assertEquals("PULSAR1854", TftPasswordGenerator.deriveDefaultSsid("PULSAR1854", "MD2B45AX4RWG01854"))
        assertEquals("PULSAR1854", TftPasswordGenerator.deriveDefaultSsid(null, "md2b45ax4rwg01854"))
        assertEquals("PULSAR", TftPasswordGenerator.deriveDefaultSsid("", ""))
    }

    @Test
    fun `tcp decoder handles resync, oversized guard, and partial frames`() {
        // Garbage byte before valid screen info request
        val buf = byteArrayOf(0x7F, 0x00, 0x01, 0x01, 0x00, 0x00)
        val first = TftTcpCodec.decodeNextFrame(buf, buf.size)
        assertNull(first.frame)
        assertEquals(1, first.consumedBytes)
        assertFalse(first.needMoreData)

        val tail = buf.copyOfRange(1, buf.size)
        val valid = TftTcpCodec.decodeNextFrame(tail, tail.size)
        assertNotNull(valid.frame)
        assertEquals(5, valid.consumedBytes)
        assertTrue(TftTcpCodec.parseClusterRequest(valid.frame!!) is ClusterTcpRequest.ScreenInfoRequest)

        // Oversized payload > 2048 drops 1 byte to resync
        val oversized = byteArrayOf(0x00, 0x02, 0x00, 0x09, 0x00, 0x00) // payloadLen = 2304
        val bad = TftTcpCodec.decodeNextFrame(oversized, oversized.size)
        assertNull(bad.frame)
        assertEquals(1, bad.consumedBytes)
        assertFalse(bad.needMoreData)

        // Incomplete payload asks for more bytes
        val partial = byteArrayOf(0x00, 0x02, 0x04, 0x00, 0x02, 0x01)
        val incomplete = TftTcpCodec.decodeNextFrame(partial, partial.size)
        assertNull(incomplete.frame)
        assertTrue(incomplete.needMoreData)
    }

    @Test
    fun `tcp touch and handlebar requests parse coordinates and recenter bit`() {
        val touchFrame = TftTcpFrame(
            type = 0x00,
            group = TftWifiProtocol.GROUP_INPUT,
            cmd = TftWifiProtocol.CMD_TOUCH,
            payload = byteArrayOf(4, 0, 0, 0x01, 0x90.toByte(), 0x00, 0xF0.toByte()) // (400, 240) DOWN
        )
        val touchReq = TftTcpCodec.parseClusterRequest(touchFrame) as ClusterTcpRequest.Touch
        assertEquals(4, touchReq.touchType)
        assertEquals(400, touchReq.x)
        assertEquals(240, touchReq.y)

        val hbRecenter = TftTcpCodec.parseClusterRequest(
            TftTcpFrame(0x00, TftWifiProtocol.GROUP_INPUT, TftWifiProtocol.CMD_HANDLEBAR_INPUT, byteArrayOf(0x08))
        ) as ClusterTcpRequest.HandlebarInput
        assertTrue(hbRecenter.recenterRequested)

        val hbOther = TftTcpCodec.parseClusterRequest(
            TftTcpFrame(0x00, TftWifiProtocol.GROUP_INPUT, TftWifiProtocol.CMD_HANDLEBAR_INPUT, byteArrayOf(0x02))
        ) as ClusterTcpRequest.HandlebarInput
        assertFalse(hbOther.recenterRequested)
    }

    @Test
    fun `tcp response builders encode exact date time, phone name, and music doc frames`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 12, 14, 35, 0)
        }
        val dt = TftTcpCodec.buildDateTimeResponse(cal)
        assertArrayEquals(
            byteArrayOf(
                0x01, 0x02, 0x03, 0x00, 0x0B,
                35,
                14, // Calendar.HOUR_OF_DAY 24-hour field (14:00 -> 14)
                12,
                10, // Calendar.NOVEMBER = 10
                0x07, 0xEA.toByte() // 2026 BE
            ),
            dt
        )

        val nameFrame = TftTcpCodec.buildPhoneNameResponse("Pixel 8")
        assertEquals(0x01.toByte(), nameFrame[0])
        assertEquals(0x02.toByte(), nameFrame[1])
        assertEquals(0x05.toByte(), nameFrame[2])
        assertEquals((7 + 5).toByte(), nameFrame[4])
        assertEquals("Pixel 8", String(nameFrame.copyOfRange(5, nameFrame.size)))

        val art = ByteArray(123) { (it * 3).toByte() }
        val sizeFrame = TftTcpCodec.buildDocSizeResponse(TftWifiProtocol.DOC_MUSIC_COVER, art)
        assertEquals(10, sizeFrame.size)
        assertEquals(127, ByteBuffer.wrap(sizeFrame, 5, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(TftWifiProtocol.DOC_MUSIC_COVER, sizeFrame[9].toInt())

        val md5Frame = TftTcpCodec.buildDocMd5Response(TftWifiProtocol.DOC_MUSIC_COVER, art)
        assertEquals(22, md5Frame.size)
        assertArrayEquals(
            MessageDigest.getInstance("MD5").digest(art),
            md5Frame.copyOfRange(6, 22)
        )

        val dataFrame = TftTcpCodec.buildDocDataResponse(TftWifiProtocol.DOC_MUSIC_COVER, art)
        assertEquals(10 + art.size + 4, dataFrame.size)
        assertEquals(TftUdpFrameEncoder.computeCrc32Int(art), ByteBuffer.wrap(dataFrame, 10 + art.size, 4).int)
    }

    @Test
    fun `udp encoder chunks multi-packet JPEG, encrypts AES-CBC, and verifies round-trip CRC32`() {
        val fakeJpeg = ByteArray(3456) { ((it * 31) xor 0x5A).toByte() }
        val chunks = TftUdpFrameEncoder.encodeJpegFrameChunks(fakeJpeg)
        assertEquals(4, chunks.size) // 1006 + 1008 + 1008 + (434 + 4 crc) = 3456 bytes across 4 chunks

        val decrypt = TftUdpFrameEncoder.createDecryptCipher()
        val firstPlain = decrypt.doFinal(chunks.first())
        val expectedSize = ByteBuffer.wrap(firstPlain, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(fakeJpeg.size, expectedSize)

        val reassembled = ByteArray(expectedSize)
        var pos = firstPlain.size - 4
        System.arraycopy(firstPlain, 4, reassembled, 0, pos)

        var parsedCrc = 0
        for (idx in 1 until chunks.size) {
            val plain = decrypt.doFinal(chunks[idx])
            assertTrue(chunks[idx].size <= 1024)
            if (idx < chunks.lastIndex) {
                System.arraycopy(plain, 0, reassembled, pos, plain.size)
                pos += plain.size
            } else {
                val tailLen = plain.size - 4
                System.arraycopy(plain, 0, reassembled, pos, tailLen)
                pos += tailLen
                parsedCrc = ByteBuffer.wrap(plain, tailLen, 4).order(ByteOrder.BIG_ENDIAN).int
            }
        }

        assertEquals(fakeJpeg.size, pos)
        assertArrayEquals(fakeJpeg, reassembled)
        assertEquals(TftUdpFrameEncoder.computeCrc32Int(fakeJpeg), parsedCrc)
    }

    @Test
    fun `udp encoder rejects oversized frames and safely handles small single-chunk frames`() {
        assertTrue(TftUdpFrameEncoder.encodeJpegFrameChunks(ByteArray(0)).isEmpty())
        assertTrue(TftUdpFrameEncoder.encodeJpegFrameChunks(ByteArray(30_000)).isEmpty())

        val tiny = ByteArray(400) { it.toByte() }
        val chunks = TftUdpFrameEncoder.encodeJpegFrameChunks(tiny)
        assertEquals(1, chunks.size)
        val plain = TftUdpFrameEncoder.createDecryptCipher().doFinal(chunks[0])
        assertEquals(4 + 400 + 4, plain.size)
        assertEquals(400, ByteBuffer.wrap(plain, 0, 4).int)
        assertArrayEquals(tiny, plain.copyOfRange(4, 404))
        assertEquals(TftUdpFrameEncoder.computeCrc32Int(tiny), ByteBuffer.wrap(plain, 404, 4).int)
    }

    @Test
    fun `adaptive jpeg quality bounds and retry math behave deterministically`() {
        assertEquals(48, TftUdpFrameEncoder.selectNextQuality(50, 26_000))
        assertEquals(51, TftUdpFrameEncoder.selectNextQuality(50, 18_000))
        assertEquals(53, TftUdpFrameEncoder.selectNextQuality(50, 15_000))
        assertEquals(25, TftUdpFrameEncoder.selectNextQuality(25, 29_000))
        assertEquals(95, TftUdpFrameEncoder.selectNextQuality(95, 5_000))

        val retryQ = TftUdpFrameEncoder.computeRetryQuality(currentQuality = 50, encodedSize = 35_000)
        assertTrue(retryQ in 25..45)
        assertEquals(31, retryQ)
    }

    @Test
    fun `nav v2 codec encodes list, detail, route preview, preference, and nav status frames`() {
        val listPayload = TftNavV2Codec.encodeIndexedNameList(
            listOf(
                NavV2IndexedPlace(index = 0, name = "Home"),
                NavV2IndexedPlace(index = 1, name = "Fuel Pump")
            )
        )
        val listFrame = TftNavV2Codec.buildResponse(TftWifiProtocol.CMD_NAV_V2_GET_FAVOURITES, listPayload)
        assertEquals(0x01.toByte(), listFrame[0])
        assertEquals(0x02.toByte(), listFrame[1])
        assertEquals(TftWifiProtocol.CMD_NAV_V2_GET_FAVOURITES.toByte(), listFrame[2])
        val payloadLen = ((listFrame[3].toInt() and 0xFF) shl 8) or (listFrame[4].toInt() and 0xFF)
        assertEquals(listPayload.size, payloadLen)
        assertEquals(2.toByte(), listFrame[5]) // count = 2
        assertEquals(0.toByte(), listFrame[6]) // index 0
        assertEquals(4.toByte(), listFrame[7]) // "Home".length
        assertEquals("Home", String(listFrame, 8, 4))

        val detail = TftNavV2Codec.encodeDetail(1, "Shell Station", "2.4 km", "6 min")
        assertEquals(1.toByte(), detail[0])
        assertEquals(13.toByte(), detail[1])

        val preview = TftNavV2Codec.encodeRoutePreview(
            source = NavV2Source.POI,
            index = 2,
            routeMode = NavV2RouteMode.AVOID_TOLLS,
            distanceText = "5.0 km",
            etaText = "11 min"
        )
        assertEquals(NavV2Source.POI.value.toByte(), preview[0])
        assertEquals(2.toByte(), preview[1])
        assertEquals(NavV2RouteMode.AVOID_TOLLS.value.toByte(), preview[2])

        val parsedPref = TftNavV2Codec.parseSetRoutePreference(byteArrayOf(0x0F, 0x02, 0x01, 0x00))
        assertNotNull(parsedPref)
        assertEquals(NavV2RouteMode.ECO, parsedPref!!.routeMode)
        assertEquals(NavV2Units.MILES, parsedPref.units)
        assertNull(TftNavV2Codec.parseSetRoutePreference(byteArrayOf(0x00, 0x00, 0x00, 0x01)))

        val navStatus = TftNavV2Codec.encodeNavStatus(NavV2NavStatus.STARTED, "OK")
        assertEquals(NavV2NavStatus.STARTED.value.toByte(), navStatus[0])
        assertEquals(2.toByte(), navStatus[1])
        assertEquals("OK", String(navStatus, 2, 2))
    }

    @Test
    fun navV2Controller_overpassParsingFallbackAndRecentMerge() {
        val rawOverpass = """
            {
              "elements": [
                {"type":"node","lat":18.5350,"lon":73.8700,"tags":{"name":"HP Petrol Pump"}},
                {"type":"way","center":{"lat":18.5220,"lon":73.8580},"tags":{"brand":"IndianOil"}}
              ]
            }
        """.trimIndent()
        val parsed = TftNavV2Controller.parseOverpassPoisJson(
            rawJson = rawOverpass,
            fallbackCategoryName = "Petrol Pump",
            originLat = 18.5204,
            originLng = 73.8567,
            units = NavV2Units.KILOMETRES,
            routeMode = NavV2RouteMode.FASTEST
        )
        assertEquals(2, parsed.size)
        // Nearest station (IndianOil at 18.5220,73.8580) must be sorted to index 0
        assertEquals(0, parsed[0].index)
        assertEquals("IndianOil", parsed[0].name)
        assertEquals(1, parsed[1].index)
        assertEquals("HP Petrol Pump", parsed[1].name)

        val fallback = TftNavV2Controller.buildFallbackPois(
            categoryIndex = 4,
            units = NavV2Units.MILES,
            routeMode = NavV2RouteMode.SHORTEST
        )
        assertEquals(1, fallback.size)
        assertTrue(fallback[0].name.contains("Bike Service"))
        assertTrue(fallback[0].distanceText.endsWith("mi") || fallback[0].distanceText.endsWith("ft"))
        assertEquals(0.0, fallback[0].latitude, 1e-6)
        assertEquals(0.0, fallback[0].longitude, 1e-6)

        val updatedJson = TftNavV2Controller.appendSavedRecentDestJson(
            existingJson = "",
            place = NavV2IndexedPlace(0, "Lonavala", 18.75, 73.40, "Lonavala, MH")
        )
        val updatedTwice = TftNavV2Controller.appendSavedRecentDestJson(
            existingJson = updatedJson,
            place = NavV2IndexedPlace(0, "Lonavala", 18.75, 73.40, "Lonavala, MH")
        )
        val merged = TftNavV2Controller.mergeRecentDestinations(
            savedJsonPlaces = TftNavV2Controller.parseSavedRecentDestsJson(updatedTwice),
            rideHistoryTitles = listOf("Bajaj Ride", "Lavasa Ghat"),
            originLat = 18.5204,
            originLng = 73.8567,
            units = NavV2Units.KILOMETRES,
            routeMode = NavV2RouteMode.FASTEST
        )
        assertEquals(2, merged.size)
        assertEquals("Lonavala", merged[0].name)
        assertEquals("Lavasa Ghat", merged[1].name)
        assertEquals("--", merged[1].distanceText)
    }

    @Test
    fun docDataResponse_matchesOemHeaderLengthAndOverflowSentinel() {
        val smallDoc = ByteArray(1234) { (it and 0xFF).toByte() }
        val smallFrame = TftTcpCodec.buildDocDataResponse(docNum = 1, docBytes = smallDoc)
        assertEquals(smallDoc.size + 14, smallFrame.size)
        assertEquals(TftWifiProtocol.CMD_DOC_DATA.toByte(), smallFrame[2])
        val smallHeaderLen = ((smallFrame[3].toInt() and 0xFF) shl 8) or (smallFrame[4].toInt() and 0xFF)
        assertEquals(smallDoc.size + 5, smallHeaderLen)

        val boundaryDoc = ByteArray(65_525) { 0x2A }
        val boundaryFrame = TftTcpCodec.buildDocDataResponse(docNum = 2, docBytes = boundaryDoc)
        val boundaryHeaderLen = ((boundaryFrame[3].toInt() and 0xFF) shl 8) or (boundaryFrame[4].toInt() and 0xFF)
        assertEquals(65_530, boundaryHeaderLen)

        val largeDoc = ByteArray(65_526) { 0x3B }
        val largeFrame = TftTcpCodec.buildDocDataResponse(docNum = 1, docBytes = largeDoc)
        assertEquals(largeDoc.size + 14, largeFrame.size)
        val overflowHeaderLen = ((largeFrame[3].toInt() and 0xFF) shl 8) or (largeFrame[4].toInt() and 0xFF)
        assertEquals(0xFFFF, overflowHeaderLen)
    }

    @Test
    fun phoneNameResponse_clipsMultibyteUtf8AtCodepointBoundary() {
        val input = "Rider_🚲🚲🚲🚲🚲🚲"
        val clipped = TftTcpCodec.clipUtf8Codepoints(input, maxUtf8Bytes = 24)
        assertTrue(clipped.size <= 24)
        val decoded = String(clipped, Charsets.UTF_8)
        assertTrue(!decoded.contains('\uFFFD'))
        assertEquals(clipped.size, decoded.toByteArray(Charsets.UTF_8).size)

        val frame = TftTcpCodec.buildPhoneNameResponse(input, maxUtf8Bytes = 24)
        val headerLen = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
        assertEquals(clipped.size + 5, headerLen)
    }
}

package com.pulsarnav.app.wifi

import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.wifi.TftCockpitDisplayController.LiveMapTouchZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class TftNavigationMapEngineTest {

    private fun encodePolyline6(points: List<GeoPoint>): String {
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for (pt in points) {
            val lat = (pt.lat * 1e6).roundToLong()
            val lng = (pt.lng * 1e6).roundToLong()
            appendSigned(lat - prevLat, sb)
            appendSigned(lng - prevLng, sb)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun appendSigned(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append(((0x20L or (v and 0x1FL)) + 63L).toInt().toChar())
            v = v shr 5
        }
        sb.append((v + 63L).toInt().toChar())
    }

    @Test
    fun parseSharedMapInput_parsesShortLinksPinsGeoNavAndQueries() {
        val shortLink = TftMapMathAndParser.parseSharedMapInput("Nandi Hills https://maps.app.goo.gl/xyz123abc")
        assertNotNull(shortLink)
        assertEquals("https://maps.app.goo.gl/xyz123abc", shortLink?.urlToResolve)
        assertEquals("Nandi Hills", shortLink?.label)

        val bangUrl = TftMapMathAndParser.parseSharedMapInput(
            "https://www.google.com/maps/place/Cubbon+Park/@12.9710,77.5900,15z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d12.9763!4d77.5929"
        )
        assertNotNull(bangUrl)
        assertEquals("Cubbon Park", bangUrl?.label)
        assertEquals(12.9763, bangUrl?.lat ?: 0.0, 1e-4)
        assertEquals(77.5929, bangUrl?.lng ?: 0.0, 1e-4)
        assertNull(bangUrl?.urlToResolve)

        val atUrl = TftMapMathAndParser.parseSharedMapInput("https://www.google.com/maps/@12.9716,77.5946,16z")
        assertNotNull(atUrl)
        assertEquals(12.9716, atUrl?.lat ?: 0.0, 1e-4)
        assertEquals(77.5946, atUrl?.lng ?: 0.0, 1e-4)

        val geoUri = TftMapMathAndParser.parseSharedMapInput("geo:13.0827,80.2707?q=Marina+Beach")
        assertNotNull(geoUri)
        assertEquals("Marina Beach", geoUri?.label)
        assertEquals(13.0827, geoUri?.lat ?: 0.0, 1e-4)
        assertEquals(80.2707, geoUri?.lng ?: 0.0, 1e-4)

        val navUri = TftMapMathAndParser.parseSharedMapInput("google.navigation:q=19.0760,72.8777")
        assertNotNull(navUri)
        assertEquals(19.0760, navUri?.lat ?: 0.0, 1e-4)
        assertEquals(72.8777, navUri?.lng ?: 0.0, 1e-4)

        val plainQuery = TftMapMathAndParser.parseSharedMapInput("MG Road Bengaluru")
        assertNotNull(plainQuery)
        assertNull(plainQuery?.lat)
        assertEquals("MG Road Bengaluru", plainQuery?.query)
    }

    @Test
    fun decodePolyline6_andLatLngToWorldPixel_roundTripAccurately() {
        val expected = listOf(
            GeoPoint(12.971600, 77.594600),
            GeoPoint(12.976300, 77.594600),
            GeoPoint(12.976300, 77.599200)
        )
        val encoded = encodePolyline6(expected)
        val decoded = TftMapMathAndParser.decodePolyline6(encoded)

        assertEquals(expected.size, decoded.size)
        for (i in expected.indices) {
            assertEquals(expected[i].lat, decoded[i].lat, 1e-5)
            assertEquals(expected[i].lng, decoded[i].lng, 1e-5)
        }

        val (wx0, wy0) = TftMapMathAndParser.latLngToWorldPixel(0.0, 0.0, zoom = 16)
        val expectedCenter = 256.0 * (1 shl 16) / 2.0
        assertEquals(expectedCenter, wx0, 1e-3)
        assertEquals(expectedCenter, wy0, 1e-3)

        val (_, wyNorth) = TftMapMathAndParser.latLngToWorldPixel(12.9716, 77.5946, zoom = 16)
        assertTrue(wyNorth < wy0)
    }

    @Test
    fun mapOsrmManeuver_coversCommonTurnForkRampRoundaboutAndArrive() {
        assertEquals(Maneuver.DESTINATION_REACHED, TftMapMathAndParser.mapOsrmManeuver("arrive", ""))
        assertEquals(Maneuver.TURN_LEFT, TftMapMathAndParser.mapOsrmManeuver("turn", "left"))
        assertEquals(Maneuver.TURN_SLIGHT_RIGHT, TftMapMathAndParser.mapOsrmManeuver("turn", "slight right"))
        assertEquals(Maneuver.U_TURN_RIGHT, TftMapMathAndParser.mapOsrmManeuver("turn", "uturn"))
        assertEquals(Maneuver.FORK_LEFT, TftMapMathAndParser.mapOsrmManeuver("fork", "slight left"))
        assertEquals(Maneuver.RAMP_RIGHT, TftMapMathAndParser.mapOsrmManeuver("off ramp", "right"))
        assertEquals(Maneuver.ROUNDABOUT_RIGHT, TftMapMathAndParser.mapOsrmManeuver("roundabout", "right"))
        assertEquals(Maneuver.MERGE, TftMapMathAndParser.mapOsrmManeuver("merge", "slight left"))
    }

    @Test
    fun parseOsrmRouteResponse_andComputeRouteProgress_tracksStepsArrivalAndOffRoute() {
        val origin = GeoPoint(12.9716, 77.5946)
        val midTurn = GeoPoint(12.9761, 77.5946) // ~500m north
        val dest = GeoPoint(12.9761, 77.5900)    // ~500m west
        val encoded = encodePolyline6(listOf(origin, midTurn, dest))

        val leg1Dist = TftMapMathAndParser.haversineMeters(origin.lat, origin.lng, midTurn.lat, midTurn.lng)
        val leg2Dist = TftMapMathAndParser.haversineMeters(midTurn.lat, midTurn.lng, dest.lat, dest.lng)
        val totalDist = leg1Dist + leg2Dist

        val osrmJson = """
            {
              "code": "Ok",
              "routes": [{
                "geometry": "$encoded",
                "distance": $totalDist,
                "duration": 120.0,
                "legs": [{
                  "steps": [
                    {
                      "distance": $leg1Dist,
                      "duration": 60.0,
                      "name": "Kasturba Road",
                      "maneuver": { "type": "depart", "modifier": "straight", "location": [${origin.lng}, ${origin.lat}] }
                    },
                    {
                      "distance": $leg2Dist,
                      "duration": 60.0,
                      "name": "Queens Road",
                      "maneuver": { "type": "turn", "modifier": "left", "location": [${midTurn.lng}, ${midTurn.lat}] }
                    },
                    {
                      "distance": 0.0,
                      "duration": 0.0,
                      "name": "",
                      "maneuver": { "type": "arrive", "location": [${dest.lng}, ${dest.lat}] }
                    }
                  ]
                }]
              }]
            }
        """.trimIndent()

        val route = TftMapMathAndParser.parseOsrmRouteResponse(osrmJson, "Cubbon Park", origin, dest)
        assertNotNull(route)
        route!!
        assertEquals(3, route.steps.size)
        assertEquals(3, route.polyline.size)

        // Rider halfway along leg 1 should see upcoming TURN_LEFT onto Queens Road
        val snapMidLeg1 = TftMapMathAndParser.computeRouteProgress(
            route = route,
            lat = 12.97385,
            lng = 77.5946,
            speedMps = 10f,
            nowMs = 1_700_000_000_000L
        )
        assertFalse(snapMidLeg1.arrived)
        assertEquals(Maneuver.TURN_LEFT, snapMidLeg1.nextStep.maneuver)
        assertEquals("Queens Road", snapMidLeg1.nextStep.streetName)
        assertTrue(snapMidLeg1.distanceToNextTurnMeters in 180..320)
        assertTrue(snapMidLeg1.offRouteMeters < 10.0)

        // Rider near destination should trigger arrival
        val snapArrived = TftMapMathAndParser.computeRouteProgress(
            route = route,
            lat = 12.9761,
            lng = 77.5901,
            speedMps = 2f
        )
        assertTrue(snapArrived.arrived)
        assertEquals(Maneuver.DESTINATION_REACHED, snapArrived.nextStep.maneuver)
        assertEquals(0, snapArrived.distanceToNextTurnMeters)

        // Rider shifted 250m east should exceed off-route threshold
        val snapOffRoute = TftMapMathAndParser.computeRouteProgress(
            route = route,
            lat = 12.97385,
            lng = 77.5975,
            speedMps = 8f
        )
        assertTrue(snapOffRoute.offRouteMeters > TftMapMathAndParser.OFF_ROUTE_REROUTE_THRESHOLD_M)
    }

    @Test
    fun screenCastTransform_normalizesCaptureSizeAspectCropsAndFitRect() {
        val portraitSize = TftScreenCastTransform.computeCaptureDimensions(1080, 2400, maxEdge = 960)
        assertEquals(432, portraitSize.width)
        assertEquals(960, portraitSize.height)

        val tallLandscapeSize = TftScreenCastTransform.computeCaptureDimensions(2400, 1080, maxEdge = 960)
        assertEquals(960, tallLandscapeSize.width)
        assertEquals(432, tallLandscapeSize.height)

        val portraitCrop = TftScreenCastTransform.computeSourceCropRect(
            contentRect = IntRect(0, 0, 432, 960),
            targetW = 800,
            targetH = 480,
            layoutMode = AppSettings.CAST_LAYOUT_FILL
        )
        assertEquals(432, portraitCrop.width)
        // 432 / (800/480) = 259.2 -> 259
        assertEquals(259, portraitCrop.height)
        assertTrue(portraitCrop.top > 0)

        val ultraWideCrop = TftScreenCastTransform.computeSourceCropRect(
            contentRect = IntRect(0, 0, 960, 432),
            targetW = 800,
            targetH = 480,
            layoutMode = AppSettings.CAST_LAYOUT_FILL
        )
        assertEquals(432, ultraWideCrop.height)
        // 432 * (800/480) = 720
        assertEquals(720, ultraWideCrop.width)
        assertEquals(120, ultraWideCrop.left)

        val fitCrop = TftScreenCastTransform.computeSourceCropRect(
            contentRect = IntRect(0, 0, 432, 960),
            layoutMode = AppSettings.CAST_LAYOUT_FIT
        )
        assertEquals(IntRect(0, 0, 432, 960), fitCrop)

        val fitDst = TftScreenCastTransform.computeFitDestinationRect(srcW = 432, srcH = 960, targetW = 800, targetH = 480)
        assertEquals(480, fitDst.height)
        assertEquals(216, fitDst.width)
        assertEquals((800 - 216) / 2, fitDst.left)
    }

    @Test
    fun resolveLiveMapTouchZone_mapsRightRailAndDaylightStrip() {
        assertEquals(LiveMapTouchZone.NONE, TftCockpitDisplayController.resolveLiveMapTouchZone(0, 760, 160))
        assertEquals(LiveMapTouchZone.ZOOM_IN, TftCockpitDisplayController.resolveLiveMapTouchZone(9, 760, 160))
        assertEquals(LiveMapTouchZone.ZOOM_OUT, TftCockpitDisplayController.resolveLiveMapTouchZone(5, 760, 220))
        assertEquals(LiveMapTouchZone.TOGGLE_HEADING, TftCockpitDisplayController.resolveLiveMapTouchZone(9, 760, 275))
        assertEquals(LiveMapTouchZone.TOGGLE_DAYLIGHT, TftCockpitDisplayController.resolveLiveMapTouchZone(9, 120, 445))
        assertEquals(LiveMapTouchZone.NONE, TftCockpitDisplayController.resolveLiveMapTouchZone(9, 300, 200))
    }
}

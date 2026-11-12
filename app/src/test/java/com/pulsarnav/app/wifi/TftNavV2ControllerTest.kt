package com.pulsarnav.app.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TftNavV2ControllerTest {

    @Test
    fun buildFallbackPois_emitsZeroCoordinatesForSearchRouting() {
        for (category in 0..5) {
            val pois = TftNavV2Controller.buildFallbackPois(
                categoryIndex = category,
                units = NavV2Units.KILOMETRES,
                routeMode = NavV2RouteMode.FASTEST
            )
            assertEquals(1, pois.size)
            assertEquals(0.0, pois[0].latitude, 1e-6)
            assertEquals(0.0, pois[0].longitude, 1e-6)
            assertTrue(pois[0].address.isNotBlank())
        }
    }

    @Test
    fun shouldGeocodeFavouriteAddress_skipsLabelPlaceholdersAndShortStrings() {
        assertFalse(TftNavV2Controller.shouldGeocodeFavouriteAddress("Home", "Home"))
        assertFalse(TftNavV2Controller.shouldGeocodeFavouriteAddress("Home", "home"))
        assertFalse(TftNavV2Controller.shouldGeocodeFavouriteAddress("Work", "Work"))
        assertFalse(TftNavV2Controller.shouldGeocodeFavouriteAddress("Work", "WORK"))
        assertFalse(TftNavV2Controller.shouldGeocodeFavouriteAddress("Favourite", "ab"))
        assertFalse(TftNavV2Controller.shouldGeocodeFavouriteAddress("Garage", "Garage"))

        assertTrue(TftNavV2Controller.shouldGeocodeFavouriteAddress("Home", "Baner Road, Pune"))
        assertTrue(TftNavV2Controller.shouldGeocodeFavouriteAddress("Work", "Hinjewadi Phase 2, Pune"))
    }

    @Test
    fun isAcceptableLocationFix_enforcesRecencyAndAccuracy() {
        assertTrue(TftNavV2Controller.isAcceptableLocationFix(ageMs = 5_000L, accuracyMeters = 18f))
        assertTrue(TftNavV2Controller.isAcceptableLocationFix(ageMs = 30 * 60_000L, accuracyMeters = 1000f))
        assertTrue(TftNavV2Controller.isAcceptableLocationFix(ageMs = 1_000L, accuracyMeters = null))

        assertFalse(TftNavV2Controller.isAcceptableLocationFix(ageMs = 30 * 60_000L + 1L, accuracyMeters = 10f))
        assertFalse(TftNavV2Controller.isAcceptableLocationFix(ageMs = -60_001L, accuracyMeters = 10f))
        assertFalse(TftNavV2Controller.isAcceptableLocationFix(ageMs = 1_000L, accuracyMeters = 0f))
        assertFalse(TftNavV2Controller.isAcceptableLocationFix(ageMs = 1_000L, accuracyMeters = 1001f))
    }
}

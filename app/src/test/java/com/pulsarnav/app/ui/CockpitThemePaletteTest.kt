package com.pulsarnav.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitThemePaletteTest {

    @Test
    fun adjustCustomAccentRgb_preservesHighContrastColorsUnchanged() {
        // Wine Red on Light surface (#FFFFFF) already clears 4.5:1
        val lightUnchanged = CockpitThemePalette.adjustCustomAccentRgb(
            r = 158, g = 27, b = 50,
            surfaceR = 255, surfaceG = 255, surfaceB = 255,
            isLight = true
        )
        assertEquals(Triple(158, 27, 50), lightUnchanged)

        // Bright mint on Dark surface (#131215) already clears 4.5:1
        val darkUnchanged = CockpitThemePalette.adjustCustomAccentRgb(
            r = 52, g = 211, b = 153,
            surfaceR = 19, surfaceG = 18, surfaceB = 21,
            isLight = false
        )
        assertEquals(Triple(52, 211, 153), darkUnchanged)
    }

    @Test
    fun adjustCustomAccentRgb_clampsLowContrastLightAndDarkColorsToWcagAA() {
        // Pure yellow (#FFFF00) on white card is unreadable (<1.1:1) until darkened
        val lightYellow = CockpitThemePalette.adjustCustomAccentRgb(
            r = 255, g = 255, b = 0,
            surfaceR = 255, surfaceG = 255, surfaceB = 255,
            isLight = true
        )
        val lightContrast = CockpitThemePalette.contrastRatioRgb(
            lightYellow.first, lightYellow.second, lightYellow.third,
            255, 255, 255
        )
        assertTrue("Expected >= 4.5 contrast in Light mode, got $lightContrast", lightContrast >= 4.5)
        assertTrue(lightYellow.first < 255 && lightYellow.second < 255)

        // Pure black (#000000) on dark surface (#131215) is invisible until lightened
        val darkBlack = CockpitThemePalette.adjustCustomAccentRgb(
            r = 0, g = 0, b = 0,
            surfaceR = 19, surfaceG = 18, surfaceB = 21,
            isLight = false
        )
        val darkContrast = CockpitThemePalette.contrastRatioRgb(
            darkBlack.first, darkBlack.second, darkBlack.third,
            19, 18, 21
        )
        assertTrue("Expected >= 4.5 contrast in Dark mode, got $darkContrast", darkContrast >= 4.5)
        assertTrue(darkBlack.first > 90)
    }

    @Test
    fun shouldUseDarkForegroundOnPrimary_picksHigherContrastTextColor() {
        assertFalse(CockpitThemePalette.shouldUseDarkForegroundOnPrimary(158, 27, 50))
        assertTrue(CockpitThemePalette.shouldUseDarkForegroundOnPrimary(245, 196, 0))
        assertTrue(CockpitThemePalette.shouldUseDarkForegroundOnPrimary(16, 185, 129))
    }
}

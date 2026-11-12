package com.pulsarnav.app.wifi

import com.pulsarnav.app.wifi.TftCockpitDisplayController.ClusterTouchZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TftCockpitDisplayControllerTest {

    @Test
    fun resolveTouchZone_matchesHitboxesForTapTypes5And9AndIgnoresType0() {
        val mediaMidX = (TftCockpitDisplayController.MEDIA_CARD_LEFT + TftCockpitDisplayController.MEDIA_CARD_RIGHT) / 2
        val mediaMidY = (TftCockpitDisplayController.MEDIA_CARD_TOP + TftCockpitDisplayController.MEDIA_CARD_BOTTOM) / 2
        val themeMidX = (TftCockpitDisplayController.FOOTER_THEME_LEFT + TftCockpitDisplayController.FOOTER_THEME_RIGHT) / 2
        val themeMidY = (TftCockpitDisplayController.FOOTER_THEME_TOP + TftCockpitDisplayController.FOOTER_THEME_BOTTOM) / 2

        assertEquals(ClusterTouchZone.NONE, TftCockpitDisplayController.resolveTouchZone(0, mediaMidX, mediaMidY))
        assertEquals(ClusterTouchZone.NONE, TftCockpitDisplayController.resolveTouchZone(0, themeMidX, themeMidY))

        assertEquals(ClusterTouchZone.MEDIA_PLAY_PAUSE, TftCockpitDisplayController.resolveTouchZone(9, mediaMidX, mediaMidY))
        assertEquals(ClusterTouchZone.MEDIA_PLAY_PAUSE, TftCockpitDisplayController.resolveTouchZone(5, TftCockpitDisplayController.MEDIA_CARD_LEFT, TftCockpitDisplayController.MEDIA_CARD_TOP))
        assertEquals(ClusterTouchZone.MEDIA_PLAY_PAUSE, TftCockpitDisplayController.resolveTouchZone(9, TftCockpitDisplayController.MEDIA_CARD_RIGHT, TftCockpitDisplayController.MEDIA_CARD_BOTTOM))

        assertEquals(ClusterTouchZone.DAYLIGHT_TOGGLE, TftCockpitDisplayController.resolveTouchZone(9, themeMidX, themeMidY))
        assertEquals(ClusterTouchZone.DAYLIGHT_TOGGLE, TftCockpitDisplayController.resolveTouchZone(5, TftCockpitDisplayController.FOOTER_THEME_LEFT, TftCockpitDisplayController.FOOTER_THEME_TOP))
        assertEquals(ClusterTouchZone.DAYLIGHT_TOGGLE, TftCockpitDisplayController.resolveTouchZone(9, TftCockpitDisplayController.FOOTER_THEME_RIGHT, TftCockpitDisplayController.FOOTER_THEME_BOTTOM))

        assertEquals(ClusterTouchZone.NONE, TftCockpitDisplayController.resolveTouchZone(9, 10, 10))
        assertEquals(ClusterTouchZone.NONE, TftCockpitDisplayController.resolveTouchZone(9, TftCockpitDisplayController.MEDIA_CARD_LEFT - 1, mediaMidY))
    }
}

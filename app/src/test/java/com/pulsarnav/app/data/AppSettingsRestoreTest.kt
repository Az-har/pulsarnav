package com.pulsarnav.app.data

import com.pulsarnav.app.data.AppSettings.RestoreAction
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsRestoreTest {

    @Test
    fun normalizeTabKey_handlesValidAndUnknownValues() {
        assertEquals(AppSettings.TAB_HOME, AppSettings.normalizeTabKey(null))
        assertEquals(AppSettings.TAB_HOME, AppSettings.normalizeTabKey(""))
        assertEquals(AppSettings.TAB_HOME, AppSettings.normalizeTabKey("2131296999"))
        assertEquals(AppSettings.TAB_FUEL, AppSettings.normalizeTabKey("FUEL"))
        assertEquals(AppSettings.TAB_SUPPORT, AppSettings.normalizeTabKey("support"))
        assertEquals(AppSettings.TAB_ACCOUNT, AppSettings.normalizeTabKey("Account"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun coerceRestoreValue_skipsTransientTabStateAndClampsCustomAndNavV2Fields() {
        assertEquals(
            RestoreAction.Skip,
            AppSettings.coerceRestoreValue(AppSettings.KEY_LAST_ACTIVE_TAB, 123456) { false }
        )
        assertEquals(
            RestoreAction.Skip,
            AppSettings.coerceRestoreValue(AppSettings.KEY_ACTIVE_TAB_KEY, "account") { false }
        )
        assertEquals(
            RestoreAction.PutInt(255),
            AppSettings.coerceRestoreValue(AppSettings.KEY_CUSTOM_ACCENT_RED, 999) { false }
        )
        assertEquals(
            RestoreAction.PutInt(0),
            AppSettings.coerceRestoreValue(AppSettings.KEY_CUSTOM_ACCENT_GREEN, -15) { false }
        )
        assertEquals(
            RestoreAction.PutInt(3),
            AppSettings.coerceRestoreValue(AppSettings.KEY_NAV_V2_ROUTE_MODE, 9) { false }
        )
        assertEquals(
            RestoreAction.PutInt(1),
            AppSettings.coerceRestoreValue(AppSettings.KEY_NAV_V2_UNITS, 4) { false }
        )
        assertEquals(
            RestoreAction.PutInt(0x0A),
            AppSettings.coerceRestoreValue(AppSettings.KEY_NAV_V2_AVOID_BITMASK, 0xFA) { false }
        )
    }

    @Test
    fun coerceRestoreValue_coercesCoordinatesAndSmsRejectAndDocPathsSafely() {
        assertEquals(
            RestoreAction.PutString("12.9716"),
            AppSettings.coerceRestoreValue(AppSettings.KEY_HOME_LAT, 12.9716) { false }
        )
        assertEquals(
            RestoreAction.PutString("77.5946"),
            AppSettings.coerceRestoreValue(AppSettings.KEY_WORK_LNG, "77.5946") { false }
        )
        assertEquals(
            RestoreAction.PutString("Riding now"),
            AppSettings.coerceRestoreValue(AppSettings.KEY_SMS_REJECT_MESSAGE, "Riding now") { false }
        )
        assertEquals(
            RestoreAction.PutBoolean(true),
            AppSettings.coerceRestoreValue(AppSettings.KEY_SMS_REJECT_ENABLED, "true") { false }
        )
        assertEquals(
            RestoreAction.PutBoolean(false),
            AppSettings.coerceRestoreValue(AppSettings.KEY_SMS_REJECT_ENABLED, 0) { false }
        )
        assertEquals(
            RestoreAction.Remove,
            AppSettings.coerceRestoreValue(AppSettings.KEY_DOC_RC_PATH, "/tmp/missing.jpg") { false }
        )
        assertEquals(
            RestoreAction.PutString("/data/user/0/rc.jpg"),
            AppSettings.coerceRestoreValue(AppSettings.KEY_DOC_RC_PATH, "/data/user/0/rc.jpg") { true }
        )
    }
}

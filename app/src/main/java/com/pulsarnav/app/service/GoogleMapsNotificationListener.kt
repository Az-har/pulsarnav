package com.pulsarnav.app.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.parser.GoogleMapsNotificationParser
import com.pulsarnav.app.parser.ManeuverMatcher
import com.pulsarnav.app.parser.ParsedNavigationStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WhatsAppAlertInfo(
    val sender: String,
    val timestamp: Long = System.currentTimeMillis()
)

class GoogleMapsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PulsarNotificationHub"
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_REVERT_DELAY_MS = 6000L

        private val ICON_KEYS = arrayOf(
            Notification.EXTRA_LARGE_ICON,
            "android.ongoingActivityNoti.chipIcon",
            "android.ongoingActivityNoti.nowbarIcon",
            "android.ongoingActivityNoti.secondIcon"
        )

        private val _currentNavStep = MutableStateFlow<ParsedNavigationStep?>(null)
        val currentNavStep: StateFlow<ParsedNavigationStep?> = _currentNavStep

        private val _lastWhatsAppAlert = MutableStateFlow<WhatsAppAlertInfo?>(null)
        val lastWhatsAppAlert: StateFlow<WhatsAppAlertInfo?> = _lastWhatsAppAlert

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val revertToNavigationRunnable = Runnable {
        val activeStep = _currentNavStep.value
        val bleManager = BajajBleManager.getInstance(applicationContext)
        if (activeStep != null) {
            Log.d(TAG, "Reverting cluster display back to active navigation stream")
            bleManager.updateNavigationStep(activeStep)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ManeuverMatcher.prime(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isServiceActive.value = true
        ManeuverMatcher.prime(this)
        Log.i(TAG, "Notification Listener connected (Google Maps TBT + WhatsApp Alerts)")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isServiceActive.value = false
        Log.w(TAG, "Notification Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        when (sbn.packageName) {
            GOOGLE_MAPS_PACKAGE -> handleGoogleMapsNotification(sbn)
            WHATSAPP_PACKAGE -> {
                if (com.pulsarnav.app.data.AppSettings.getInstance(applicationContext).isWhatsAppAlertsEnabled) {
                    handleWhatsAppNotification(sbn)
                }
            }
        }
    }

    private fun handleGoogleMapsNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Verify it is an ongoing navigation notification
        if (!isNavigationNotification(notification, extras)) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subtext = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val primaryInfo = extras.getCharSequence("android.ongoingActivityNoti.primaryInfo")?.toString()
            ?: extras.getCharSequence("android.ongoingActivityNoti.nowbarPrimaryInfo")?.toString()
            ?: extras.getCharSequence("android.ongoingActivityNoti.chipExpandedText")?.toString()

        val secondaryInfo = extras.getCharSequence("android.ongoingActivityNoti.secondaryInfo")?.toString()
            ?: extras.getCharSequence("android.ongoingActivityNoti.nowbarSecondaryInfo")?.toString()

        val progressMax = try {
            if (extras.containsKey(Notification.EXTRA_PROGRESS_MAX)) {
                extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1)
            } else if (extras.containsKey("android.progressMax")) {
                extras.getInt("android.progressMax", -1)
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }

        val iconObj = extractIconObject(extras)

        val parsedStep = GoogleMapsNotificationParser.parse(
            title = title,
            text = text,
            subtext = subtext,
            primaryInfo = primaryInfo,
            secondaryInfo = secondaryInfo,
            progressMax = progressMax,
            iconObj = iconObj
        ) ?: return

        _currentNavStep.value = parsedStep

        Log.d(TAG, "Google Maps TBT: ${parsedStep.maneuver.description} in ${parsedStep.distanceMeters}m on ${parsedStep.streetName} (ETA: ${parsedStep.etaHour}:${parsedStep.etaMinute})")

        // Ensure background persistent service is running while navigating
        BikeBleForegroundService.start(applicationContext)

        val bleManager = BajajBleManager.getInstance(applicationContext)
        bleManager.updateNavigationStep(parsedStep)
    }

    private fun isNavigationNotification(n: Notification, ex: Bundle): Boolean {
        if (Notification.CATEGORY_NAVIGATION == n.category) return true
        if (n.category?.contains("nav", ignoreCase = true) == true) return true
        if (ex.containsKey("android.ongoingActivityNoti.primaryInfo")) return true
        if (ex.containsKey("android.ongoingActivityNoti.style")) return true
        if (n.channelId?.contains("navigation", ignoreCase = true) == true ||
            n.channelId?.contains("foreground", ignoreCase = true) == true) return true
        return ex.containsKey("android.subText")
    }

    private fun extractIconObject(ex: Bundle): Any? {
        for (key in ICON_KEYS) {
            val obj = try {
                ex.get(key)
            } catch (_: Exception) {
                null
            }
            if (obj != null) return obj
        }
        return null
    }

    private fun handleWhatsAppNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val senderTitle = extras.getString(Notification.EXTRA_TITLE) ?: return

        if (senderTitle.contains("WhatsApp", ignoreCase = true) ||
            senderTitle.contains("Checking for new messages", ignoreCase = true) ||
            senderTitle.contains("messages from", ignoreCase = true)) {
            return
        }

        Log.i(TAG, "WhatsApp message alert intercepted from: $senderTitle")
        _lastWhatsAppAlert.value = WhatsAppAlertInfo(senderTitle)

        val bleManager = BajajBleManager.getInstance(applicationContext)
        bleManager.sendWhatsAppAlert(senderTitle)

        mainHandler.removeCallbacks(revertToNavigationRunnable)
        mainHandler.postDelayed(revertToNavigationRunnable, WHATSAPP_REVERT_DELAY_MS)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == GOOGLE_MAPS_PACKAGE) {
            _currentNavStep.value = null
            Log.i(TAG, "Google Maps navigation ended -> sending stop frame")
            val bleManager = BajajBleManager.getInstance(applicationContext)
            bleManager.stopNavigation()
        }
    }
}

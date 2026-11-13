package com.pulsarnav.app.service

import android.app.Notification
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.debug.DebugCategory
import com.pulsarnav.app.debug.DebugLogRepository
import com.pulsarnav.app.parser.GoogleMapsNotificationParser
import com.pulsarnav.app.parser.ManeuverMatcher
import com.pulsarnav.app.parser.ParsedNavigationStep
import com.pulsarnav.app.protocol.AlertType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WhatsAppAlertInfo(
    val sender: String,
    val timestamp: Long = System.currentTimeMillis(),
    val alertType: AlertType = AlertType.WHATSAPP
)

data class NotificationListenerDiagnostics(
    val isConnected: Boolean = false,
    val mapsActiveInShade: Boolean = false,
    val lastNavPackage: String = "",
    val lastNavSummary: String = "",
    val lastNavTimestampMs: Long = 0L,
    val totalNavUpdates: Long = 0L,
    val lastIgnoredMapsSummary: String = ""
)

class GoogleMapsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PulsarNotificationHub"
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val GOOGLE_MAPS_LITE_PACKAGE = "com.google.android.apps.mapslite"
        private const val WAZE_PACKAGE = "com.waze"
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val WHATSAPP_REVERT_DELAY_MS = 6000L

        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.vivo.message",
            "com.coloros.mms",
            "com.oneplus.mms",
            "com.xiaomi.mms",
            "com.sonyericsson.conversations",
            "com.asus.message"
        )

        internal fun isKnownSmsPackage(packageName: String?, defaultSmsPackage: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            if (!defaultSmsPackage.isNullOrBlank() && packageName == defaultSmsPackage) return true
            return packageName in SMS_PACKAGES
        }

        internal const val ALERT_DEBOUNCE_WINDOW_MS = 3500L

        internal fun shouldSuppressAlert(
            alertType: AlertType,
            senderTitle: String,
            nowMs: Long,
            timestampsMap: MutableMap<String, Long>
        ): Boolean {
            val debounceKey = "${alertType.name}:$senderTitle"
            val lastSent = timestampsMap[debounceKey] ?: 0L
            if (nowMs - lastSent < ALERT_DEBOUNCE_WINDOW_MS) {
                return true
            }
            timestampsMap[debounceKey] = nowMs
            if (timestampsMap.size > 50) {
                timestampsMap.entries.removeIf { nowMs - it.value > 60_000L }
            }
            return false
        }

        private val ICON_KEYS = arrayOf(
            Notification.EXTRA_LARGE_ICON,
            Notification.EXTRA_PICTURE,
            "android.ongoingActivityNoti.chipIcon",
            "android.ongoingActivityNoti.nowbarIcon",
            "android.ongoingActivityNoti.secondIcon"
        )

        private val _currentNavStep = MutableStateFlow<ParsedNavigationStep?>(null)
        val currentNavStep: StateFlow<ParsedNavigationStep?> = _currentNavStep

        private val _lastWhatsAppAlert = MutableStateFlow<WhatsAppAlertInfo?>(null)
        val lastWhatsAppAlert: StateFlow<WhatsAppAlertInfo?> = _lastWhatsAppAlert

        private val _diagnostics = MutableStateFlow(NotificationListenerDiagnostics())
        val diagnostics: StateFlow<NotificationListenerDiagnostics> = _diagnostics

        fun hasActiveExternalNavigation(): Boolean = _currentNavStep.value != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var activeNavKey: String? = null
    @Volatile
    private var activeNavPackage: String? = null

    private val revertToNavigationRunnable = Runnable {
        _lastWhatsAppAlert.value = null
        val bleManager = BajajBleManager.getInstance(applicationContext)
        val activeStep = _currentNavStep.value
            ?: com.pulsarnav.app.wifi.TftNavigationMapEngine.getInstance(applicationContext).getCurrentStepForHud()
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
        ManeuverMatcher.prime(this)
        _diagnostics.value = _diagnostics.value.copy(isConnected = true)
        scanExistingActiveNotifications()
        Log.i(TAG, "Notification Listener connected (Google Maps TBT + WhatsApp Alerts)")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _diagnostics.value = _diagnostics.value.copy(isConnected = false, mapsActiveInShade = false)
        Log.w(TAG, "Notification Listener disconnected")
        DebugLogRepository.error("NotifListener", "Notification Listener service disconnected")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(revertToNavigationRunnable)
        super.onDestroy()
    }

    private fun scanExistingActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            var mapsPresent = false
            for (sbn in active) {
                if (sbn == null) continue
                when (sbn.packageName) {
                    GOOGLE_MAPS_PACKAGE, GOOGLE_MAPS_LITE_PACKAGE -> {
                        mapsPresent = true
                        handleNavigationNotification(sbn, "Google Maps")
                    }
                    WAZE_PACKAGE -> {
                        mapsPresent = true
                        handleNavigationNotification(sbn, "Waze")
                    }
                    ANDROID_AUTO_PACKAGE -> {
                        mapsPresent = true
                        handleNavigationNotification(sbn, "Android Auto")
                    }
                }
            }
            _diagnostics.value = _diagnostics.value.copy(mapsActiveInShade = mapsPresent)
        } catch (e: Exception) {
            Log.d(TAG, "Unable to inspect initial activeNotifications: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!_diagnostics.value.isConnected) {
            _diagnostics.value = _diagnostics.value.copy(isConnected = true)
        }

        when (sbn.packageName) {
            GOOGLE_MAPS_PACKAGE, GOOGLE_MAPS_LITE_PACKAGE -> {
                _diagnostics.value = _diagnostics.value.copy(mapsActiveInShade = true)
                handleNavigationNotification(sbn, "Google Maps")
            }
            WAZE_PACKAGE -> {
                _diagnostics.value = _diagnostics.value.copy(mapsActiveInShade = true)
                handleNavigationNotification(sbn, "Waze")
            }
            ANDROID_AUTO_PACKAGE -> {
                _diagnostics.value = _diagnostics.value.copy(mapsActiveInShade = true)
                handleNavigationNotification(sbn, "Android Auto")
            }
            WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE -> {
                if (com.pulsarnav.app.data.AppSettings.getInstance(applicationContext).isWhatsAppAlertsEnabled) {
                    handleMessagingNotification(sbn, AlertType.WHATSAPP)
                }
            }
            else -> {
                val defaultSmsPkg = try {
                    Telephony.Sms.getDefaultSmsPackage(applicationContext)
                } catch (_: Exception) {
                    null
                }
                if (isKnownSmsPackage(sbn.packageName, defaultSmsPkg) &&
                    com.pulsarnav.app.data.AppSettings.getInstance(applicationContext).isWhatsAppAlertsEnabled
                ) {
                    handleMessagingNotification(sbn, AlertType.SMS)
                }
            }
        }
    }

    private fun handleNavigationNotification(sbn: StatusBarNotification, sourceName: String) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subtext = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        // Verify it is an ongoing navigation notification
        if (!isNavigationNotification(notification, extras)) {
            if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && !title.isNullOrBlank()) {
                _diagnostics.value = _diagnostics.value.copy(
                    lastIgnoredMapsSummary = "${sbn.packageName}: cat=${notification.category ?: "none"} flags=0x${notification.flags.toString(16)}"
                )
            }
            return
        }

        val settings = com.pulsarnav.app.data.AppSettings.getInstance(applicationContext)
        if (settings.isSkipClusterNavWhenHeadunit && sourceName == "Android Auto") {
            Log.d(TAG, "Skipping BLE cluster update because Headunit navigation mirroring is disabled in settings")
            return
        }

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

        val iconObj = extractIconObject(notification, extras)

        val parsedStep = GoogleMapsNotificationParser.parse(
            title = title,
            text = text,
            subtext = subtext,
            primaryInfo = primaryInfo,
            secondaryInfo = secondaryInfo,
            progressMax = progressMax,
            iconObj = iconObj
        ) ?: return

        activeNavKey = sbn.key
        activeNavPackage = sbn.packageName
        _currentNavStep.value = parsedStep
        val prevDiag = _diagnostics.value
        _diagnostics.value = prevDiag.copy(
            isConnected = true,
            mapsActiveInShade = true,
            lastNavPackage = sourceName,
            lastNavSummary = "${parsedStep.maneuver.description} in ${parsedStep.distanceMeters}m (${parsedStep.streetName.ifBlank { "Route" }})",
            lastNavTimestampMs = System.currentTimeMillis(),
            totalNavUpdates = prevDiag.totalNavUpdates + 1L,
            lastIgnoredMapsSummary = ""
        )

        Log.d(TAG, "[$sourceName] TBT: ${parsedStep.maneuver.description} in ${parsedStep.distanceMeters}m (ETA: ${parsedStep.etaHour}:${parsedStep.etaMinute})")
        DebugLogRepository.logRateLimited(
            key = "maps_${parsedStep.maneuver.name}_${parsedStep.distanceMeters}",
            minIntervalMs = 2_500L,
            category = DebugCategory.MAPS,
            tag = sourceName,
            summary = "${parsedStep.maneuver.description} in ${parsedStep.distanceMeters}m",
            detail = parsedStep.streetName
        )

        // Ensure background persistent service is running while navigating without spamming startForegroundService
        if (!BikeBleForegroundService.isRunning()) {
            BikeBleForegroundService.start(applicationContext)
        }

        val bleManager = BajajBleManager.getInstance(applicationContext)
        bleManager.updateNavigationStep(parsedStep)
    }

    private fun isNavigationNotification(n: Notification, ex: Bundle): Boolean {
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if (Notification.CATEGORY_NAVIGATION == n.category) return true
        if (n.category?.contains("nav", ignoreCase = true) == true) return true
        if (ex.containsKey("android.ongoingActivityNoti.primaryInfo")) return true
        if (ex.containsKey("android.ongoingActivityNoti.nowbarPrimaryInfo")) return true
        if (ex.containsKey("android.ongoingActivityNoti.chipExpandedText")) return true
        if (ex.containsKey("android.ongoingActivityNoti.style")) return true

        val channelId = n.channelId?.lowercase()
        if (channelId != null && (
                    channelId.contains("navigation") ||
                    channelId.contains("foreground") ||
                    channelId.contains("waze") ||
                    channelId.contains("drive") ||          // Waze: "WAZE_DRIVE"
                    channelId.contains("gearhead") ||
                    channelId.contains("projection")
                )) return true

        return (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 &&
            (ex.containsKey(Notification.EXTRA_TITLE) ||
                ex.containsKey(Notification.EXTRA_TEXT) ||
                ex.containsKey(Notification.EXTRA_BIG_TEXT))
    }

    private fun extractIconObject(notification: Notification, ex: Bundle): Any? {
        for (key in ICON_KEYS) {
            val obj = try {
                ex.get(key)
            } catch (_: Exception) {
                null
            }
            if (obj != null) return obj
        }
        // Waze stores the maneuver turn arrow in notification.smallIcon (not in extras).
        // Fall back to it so the ManeuverMatcher icon-based classifier gets a chance to run.
        return try {
            notification.smallIcon
        } catch (_: Exception) {
            null
        }
    }

    private val lastAlertTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun handleMessagingNotification(sbn: StatusBarNotification, alertType: AlertType) {
        val notification = sbn.notification ?: return
        if ((notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY)) != 0) return

        val extras = notification.extras ?: return

        // Robust extraction: WhatsApp/Messages pass SpannableString (CharSequence), so extras.getString(...) returns null!
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence("android.title")?.toString()
            ?: return

        // Filter system / background sync / call / generic notices
        if (rawTitle.isBlank() ||
            rawTitle.equals("WhatsApp", ignoreCase = true) ||
            rawTitle.equals("Messages", ignoreCase = true) ||
            rawTitle.contains("Checking for new messages", ignoreCase = true) ||
            rawTitle.contains("Messages is doing work", ignoreCase = true) ||
            rawTitle.contains("Spam & blocked", ignoreCase = true) ||
            rawTitle.contains("messages from", ignoreCase = true) ||
            rawTitle.contains("WhatsApp Web", ignoreCase = true) ||
            rawTitle.contains("Calling", ignoreCase = true) ||
            rawTitle.contains("Ongoing call", ignoreCase = true)
        ) {
            return
        }

        val senderTitle = rawTitle.trim()
        if (senderTitle.isBlank()) return

        // Debounce rapid duplicate notifications from the same sender (e.g. typing updates or burst messages)
        val now = android.os.SystemClock.elapsedRealtime()
        if (shouldSuppressAlert(alertType, senderTitle, now, lastAlertTimestamps)) {
            Log.d(TAG, "Suppressed duplicate/rapid ${alertType.name}:$senderTitle notification (debounce active)")
            return
        }

        val channelTag = if (alertType == AlertType.SMS) "SMS" else "WhatsApp"
        Log.i(TAG, "$channelTag alert intercepted from: ${DebugLogRepository.maskPersonName(senderTitle)}")
        DebugLogRepository.telephony(
            "$channelTag Alert",
            "0410 queued from ${DebugLogRepository.maskPersonName(senderTitle)}"
        )
        _lastWhatsAppAlert.value = WhatsAppAlertInfo(senderTitle, alertType = alertType)

        val bleManager = BajajBleManager.getInstance(applicationContext)
        bleManager.sendWhatsAppAlert(senderTitle, alertType)

        mainHandler.removeCallbacks(revertToNavigationRunnable)
        mainHandler.postDelayed(revertToNavigationRunnable, WHATSAPP_REVERT_DELAY_MS)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == GOOGLE_MAPS_PACKAGE || pkg == GOOGLE_MAPS_LITE_PACKAGE ||
            pkg == WAZE_PACKAGE || pkg == ANDROID_AUTO_PACKAGE
        ) {
            val key = sbn.key
            val matchesTrackedKey = activeNavKey != null && key == activeNavKey
            val matchesFallbackPkg = activeNavKey == null && pkg == activeNavPackage &&
                (sbn.notification?.let { isNavigationNotification(it, it.extras ?: Bundle.EMPTY) } == true)
            if (!matchesTrackedKey && !matchesFallbackPkg) {
                return
            }
            activeNavKey = null
            activeNavPackage = null
            _currentNavStep.value = null
            _diagnostics.value = _diagnostics.value.copy(mapsActiveInShade = false)
            val mapEngine = com.pulsarnav.app.wifi.TftNavigationMapEngine.getInstance(applicationContext)
            if (mapEngine.hasActiveTbtOwner()) {
                mapEngine.getCurrentStepForHud()?.let { step ->
                    Log.i(TAG, "External Maps notification removed ($pkg) -> keeping internal Live Map TBT active")
                    BajajBleManager.getInstance(applicationContext).updateNavigationStep(step)
                }
            } else {
                Log.i(TAG, "Navigation session ended ($pkg) -> sending stop frame")
                val bleManager = BajajBleManager.getInstance(applicationContext)
                bleManager.stopNavigation()
            }
        }
    }
}

package com.pulsarnav.app.navigation

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pulsarnav.app.R
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.service.BikeBleForegroundService

/**
 * Shared turn-by-turn navigation launcher used by the in-app quick actions,
 * BLE "Take Me Home" cluster switchgear triggers, and Wi-Fi NAV_V2 route starts.
 */
object NavigationIntentLauncher {

    private const val TAG = "NavIntentLauncher"
    private const val NAV_LAUNCH_CHANNEL_ID = "pulsar_nav_launch_channel"
    private const val NAV_LAUNCH_NOTIFICATION_ID = 1002

    fun hasValidCoordinates(lat: Double, lng: Double): Boolean {
        return !(lat == 0.0 && lng == 0.0) &&
            lat in -90.0..90.0 &&
            lng in -180.0..180.0 &&
            !lat.isNaN() &&
            !lng.isNaN()
    }

    fun launchHomeNavigation(context: Context): Boolean {
        val settings = AppSettings.getInstance(context)
        val homeQuery = settings.homeAddress.trim()
        val lat = settings.homeLat
        val lng = settings.homeLng
        if (homeQuery.isBlank() && !hasValidCoordinates(lat, lng)) {
            Log.w(TAG, "Take Me Home requested, but no home address or coordinates are configured")
            return false
        }
        return launchNavigation(
            context = context,
            query = homeQuery.ifBlank { "Home" },
            lat = lat,
            lng = lng
        )
    }

    fun launchNavigation(
        context: Context,
        query: String,
        lat: Double = 0.0,
        lng: Double = 0.0
    ): Boolean {
        val hasCoords = hasValidCoordinates(lat, lng)
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank() && !hasCoords) {
            Log.w(TAG, "Cannot launch navigation: empty destination")
            return false
        }

        val targetString = if (hasCoords) "$lat,$lng" else trimmedQuery
        val displayLabel = trimmedQuery.ifBlank { targetString }
        val settings = AppSettings.getInstance(context)
        val needsNewTask = context !is Activity

        if (!BikeBleForegroundService.isRunning()) {
            try {
                BikeBleForegroundService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start foreground service before nav launch: ${e.message}")
            }
        }

        val candidateIntents = buildCandidateIntents(settings, targetString, trimmedQuery, lat, lng, hasCoords, needsNewTask)
        val resolvableIntent = candidateIntents.firstOrNull { intent ->
            intent.resolveActivity(context.packageManager) != null
        } ?: candidateIntents.firstOrNull()

        val foregroundInteractive = !needsNewTask || isAppInteractiveInForeground(context)
        if (!foregroundInteractive && resolvableIntent != null) {
            // Android 10-15 Background Activity Launch (BAL) restrictions may silently suppress
            // background startActivity() calls; post a high-priority heads-up notification so
            // the rider still has a single-tap fallback alongside the direct attempt.
            postNavigationLaunchNotification(context, displayLabel, resolvableIntent)
        }

        for (intent in candidateIntents) {
            if (tryStartActivityIntent(context, intent)) {
                return true
            }
        }

        if (resolvableIntent != null) {
            postNavigationLaunchNotification(context, displayLabel, resolvableIntent)
        }
        Log.e(TAG, "Could not launch any navigation app directly")
        return false
    }

    private fun buildCandidateIntents(
        settings: AppSettings,
        targetString: String,
        trimmedQuery: String,
        lat: Double,
        lng: Double,
        hasCoords: Boolean,
        addNewTask: Boolean
    ): List<Intent> {
        val uris = mutableListOf<Pair<String, String?>>()
        if (settings.preferredNavApp == AppSettings.NAV_APP_WAZE) {
            val wazeDeepLink = if (hasCoords) {
                "waze://?ll=$lat,$lng&navigate=yes"
            } else {
                "waze://?q=${Uri.encode(trimmedQuery)}&navigate=yes"
            }
            uris += wazeDeepLink to "com.waze"
            val wazeWebLink = if (hasCoords) {
                "https://waze.com/ul?ll=$lat,$lng&navigate=yes"
            } else {
                "https://waze.com/ul?q=${Uri.encode(trimmedQuery)}&navigate=yes"
            }
            uris += wazeWebLink to null
        }

        val mode = if (settings.isTwoWheelerMode) "l" else "d"
        uris += "google.navigation:q=${Uri.encode(targetString)}&mode=$mode" to "com.google.android.apps.maps"

        val travelMode = if (settings.isTwoWheelerMode) "two_wheeler" else "driving"
        uris += "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(targetString)}&travelmode=$travelMode" to null

        return uris.map { (uriString, pkg) ->
            Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                if (!pkg.isNullOrBlank()) {
                    setPackage(pkg)
                }
                if (addNewTask) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
    }

    private fun tryStartActivityIntent(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isAppInteractiveInForeground(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) return false
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km != null && km.isKeyguardLocked) return false
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val running = am.runningAppProcesses ?: return false
            val pkg = context.packageName
            running.any {
                it.processName == pkg &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun postNavigationLaunchNotification(
        context: Context,
        destinationLabel: String,
        navIntent: Intent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                NAV_LAUNCH_CHANNEL_ID,
                "Navigation Launch Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tap to open turn-by-turn navigation triggered from the motorcycle cluster"
            }
            manager.createNotificationChannel(channel)
            val safeIntent = Intent(navIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NAV_LAUNCH_NOTIFICATION_ID,
                safeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val sanitizedTitle = if (destinationLabel.isBlank()) "Navigation Ready" else "Navigate: ${destinationLabel.take(40)}"
            val notification = NotificationCompat.Builder(context, NAV_LAUNCH_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(sanitizedTitle)
                .setContentText("Tap to open turn-by-turn navigation triggered by bike switchgear")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setTimeoutAfter(45_000L)
                .build()
            manager.notify(NAV_LAUNCH_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post navigation BAL fallback notification: ${e.message}")
        }
    }
}

package com.pulsarnav.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.ble.BleConnectionState
import com.pulsarnav.app.ui.MainActivity
import com.pulsarnav.app.wifi.TftWifiMirrorManager

class BikeBleForegroundService : Service {

    constructor() : super()

    companion object {
        private const val TAG = "BikeBleForegroundService"
        const val CHANNEL_ID = "pulsar_nav_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_TFT_COCKPIT_MIRROR = "com.pulsarnav.action.START_TFT_COCKPIT_MIRROR"
        const val ACTION_START_TFT_LIVE_CAST = "com.pulsarnav.action.START_TFT_LIVE_CAST"
        const val ACTION_STOP_TFT_MIRROR = "com.pulsarnav.action.STOP_TFT_MIRROR"
        const val ACTION_DROP_MEDIA_PROJECTION = "com.pulsarnav.action.DROP_MEDIA_PROJECTION"
        const val ACTION_START_NAV_LOCATION = "com.pulsarnav.action.START_NAV_LOCATION"
        const val ACTION_STOP_NAV_LOCATION = "com.pulsarnav.action.STOP_NAV_LOCATION"
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val EXTRA_KEEP_WIFI_MIRROR = "extra_keep_wifi_mirror"

        @Volatile
        private var isRunning = false

        @Volatile
        private var isWifiMirrorActive = false

        @Volatile
        private var isMediaProjectionActive = false

        @Volatile
        private var isNavLocationActive = false

        @Volatile
        private var activeInstance: BikeBleForegroundService? = null

        fun isRunning(): Boolean = isRunning

        fun start(context: Context) {
            if (isRunning) {
                Log.d(TAG, "BikeBleForegroundService is already running; skipping start request.")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasBtConnect = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasBtConnect) {
                    Log.w(TAG, "Cannot start BikeBleForegroundService: BLUETOOTH_CONNECT permission not granted.")
                    return
                }
            }
            launchServiceIntent(context, Intent(context, BikeBleForegroundService::class.java))
        }

        fun startForTftCockpitMirror(context: Context) {
            val intent = Intent(context, BikeBleForegroundService::class.java).apply {
                action = ACTION_START_TFT_COCKPIT_MIRROR
            }
            launchServiceIntent(context, intent)
        }

        fun startForTftLiveCast(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, BikeBleForegroundService::class.java).apply {
                action = ACTION_START_TFT_LIVE_CAST
                putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, data)
            }
            launchServiceIntent(context, intent)
        }

        fun stopTftMirror(context: Context) {
            if (!isRunning) {
                isWifiMirrorActive = false
                isMediaProjectionActive = false
                TftWifiMirrorManager.getInstance(context).stopMirroring()
                return
            }
            val intent = Intent(context, BikeBleForegroundService::class.java).apply {
                action = ACTION_STOP_TFT_MIRROR
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                isWifiMirrorActive = false
                isMediaProjectionActive = false
                TftWifiMirrorManager.getInstance(context).stopMirroring()
            }
        }

        fun startForLocationNavigation(context: Context) {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                Log.w(TAG, "Cannot start FGS for location navigation: location permission missing")
                return
            }
            val intent = Intent(context, BikeBleForegroundService::class.java).apply {
                action = ACTION_START_NAV_LOCATION
            }
            launchServiceIntent(context, intent)
        }

        fun stopForLocationNavigation(context: Context) {
            if (!isRunning) {
                isNavLocationActive = false
                return
            }
            val intent = Intent(context, BikeBleForegroundService::class.java).apply {
                action = ACTION_STOP_NAV_LOCATION
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                isNavLocationActive = false
            }
        }

        fun dropMediaProjection(context: Context, keepWifiMirror: Boolean = true) {
            val inst = activeInstance
            if (inst != null && isRunning) {
                inst.dropMediaProjectionInternal(keepWifiMirror)
                return
            }
            if (!isRunning) {
                isMediaProjectionActive = false
                if (!keepWifiMirror) {
                    isWifiMirrorActive = false
                }
                return
            }
            val intent = Intent(context, BikeBleForegroundService::class.java).apply {
                action = ACTION_DROP_MEDIA_PROJECTION
                putExtra(EXTRA_KEEP_WIFI_MIRROR, keepWifiMirror)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                isMediaProjectionActive = false
                if (!keepWifiMirror) {
                    isWifiMirrorActive = false
                }
                Log.w(TAG, "Failed to update FGS MediaProjection state: ${e.message}")
            }
        }

        private fun launchServiceIntent(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(
                        TAG,
                        "Skipped starting BikeBleForegroundService from background (Android 12+ restriction): ${e.message}"
                    )
                } else {
                    Log.e(TAG, "Failed to start BikeBleForegroundService: ${e.message}", e)
                }
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, BikeBleForegroundService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop BikeBleForegroundService: ${e.message}", e)
            }
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var isScreenLockReceiverRegistered = false

    private val screenLockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                TftWifiMirrorManager.getInstance(this@BikeBleForegroundService).handleScreenLockedDuringCast()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        acquireWakeLock()
        initCallManagerIfPermitted()
        try {
            val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
            ContextCompat.registerReceiver(this, screenLockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isScreenLockReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering screen lock receiver: ${e.message}")
        }
        // Foreground promotion intentionally happens inside onStartCommand() once the Intent action
        // (BLE vs TFT Wi-Fi Mirror vs MediaProjection) is known, preventing premature BLUETOOTH_CONNECT
        // rejections or ForegroundServiceDidNotStartInTimeException during Wi-Fi-only launches.
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val lock = wakeLock ?: pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PulsarNav:BleKeepAliveWakeLock")?.apply {
                setReferenceCounted(false)
            }
            wakeLock = lock
            if (lock != null && !lock.isHeld) {
                lock.acquire(12 * 60 * 60 * 1000L) // 12-hour safe upper bound
                Log.d(TAG, "Partial WakeLock acquired for persistent background link")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun updateWifiLock(enable: Boolean) {
        try {
            if (enable) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                // Note: WIFI_MODE_FULL_LOW_LATENCY is explicitly deactivated by Android OS when the screen turns off.
                // WIFI_MODE_FULL_HIGH_PERF keeps the Wi-Fi radio active and awake continuously, even when the screen is locked/off.
                val lock = wifiLock ?: wm?.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "PulsarNav:TftSoftApWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                }
                wifiLock = lock
                if (lock != null && !lock.isHeld) {
                    lock.acquire()
                    Log.d(TAG, "High-performance screen-off WifiLock acquired for TFT SoftAP streaming")
                }
            } else {
                wifiLock?.let {
                    if (it.isHeld) it.release()
                }
                wifiLock = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle WifiLock ($enable): ${e.message}")
        }
    }

    private fun initCallManagerIfPermitted() {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.READ_PHONE_STATE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                com.pulsarnav.app.telephony.CallManager.getInstance(this).initialize()
            }
        } catch (e: Exception) {
            Log.w(TAG, "CallManager init in service error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        initCallManagerIfPermitted()

        when (intent?.action) {
            ACTION_START_TFT_COCKPIT_MIRROR -> {
                isWifiMirrorActive = true
                isMediaProjectionActive = false
                updateWifiLock(true)
                promoteToForeground(includeMediaProjection = false, allowWifiWithoutBt = true)
                TftWifiMirrorManager.getInstance(this).startPreferredHeadlessMirroring()
            }
            ACTION_START_TFT_LIVE_CAST -> {
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)
                if (data != null) {
                    isWifiMirrorActive = true
                    isMediaProjectionActive = true
                    updateWifiLock(true)
                    // Android 14+ requires startForeground with MEDIA_PROJECTION BEFORE getMediaProjection().
                    promoteToForeground(includeMediaProjection = true, allowWifiWithoutBt = true)
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                    val projection = mpm?.getMediaProjection(resultCode, data)
                    if (projection != null) {
                        val started = TftWifiMirrorManager.getInstance(this).startLiveScreenMirroring(projection)
                        if (!started) {
                            Log.w(TAG, "Live screen cast failed to start; dropping MediaProjection FGS type")
                            isMediaProjectionActive = false
                            isWifiMirrorActive = false
                            updateWifiLock(false)
                            if (isBleIdleForService()) {
                                isRunning = false
                                stopSelf()
                                return START_NOT_STICKY
                            } else {
                                promoteToForeground(includeMediaProjection = false, allowWifiWithoutBt = false)
                            }
                        }
                    } else {
                        Log.w(TAG, "MediaProjection handle was null; falling back to Live Map / HUD")
                        isMediaProjectionActive = false
                        promoteToForeground(includeMediaProjection = false, allowWifiWithoutBt = true)
                        TftWifiMirrorManager.getInstance(this).startPreferredHeadlessMirroring()
                    }
                } else {
                    isWifiMirrorActive = true
                    isMediaProjectionActive = false
                    updateWifiLock(true)
                    promoteToForeground(includeMediaProjection = false, allowWifiWithoutBt = true)
                    TftWifiMirrorManager.getInstance(this).startPreferredHeadlessMirroring()
                }
            }
            ACTION_DROP_MEDIA_PROJECTION -> {
                val keepWifi = intent.getBooleanExtra(EXTRA_KEEP_WIFI_MIRROR, true)
                dropMediaProjectionInternal(keepWifi)
                if (!isRunning) return START_NOT_STICKY
            }
            ACTION_STOP_TFT_MIRROR -> {
                isWifiMirrorActive = false
                isMediaProjectionActive = false
                updateWifiLock(false)
                TftWifiMirrorManager.getInstance(this).stopMirroring()
                if (!isNavLocationActive && isBleIdleForService()) {
                    promoteMinimalBeforeAbort()
                    isRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                promoteToForeground(
                    includeMediaProjection = false,
                    allowWifiWithoutBt = false,
                    allowLocationOnly = isNavLocationActive
                )
            }
            ACTION_START_NAV_LOCATION -> {
                isNavLocationActive = true
                val started = promoteToForeground(
                    includeMediaProjection = isMediaProjectionActive,
                    allowWifiWithoutBt = isWifiMirrorActive,
                    allowLocationOnly = true
                )
                if (!started) {
                    isNavLocationActive = false
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP_NAV_LOCATION -> {
                isNavLocationActive = false
                if (!isWifiMirrorActive && !isMediaProjectionActive && isBleIdleForService()) {
                    promoteMinimalBeforeAbort()
                    isRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                promoteToForeground(
                    includeMediaProjection = isMediaProjectionActive,
                    allowWifiWithoutBt = isWifiMirrorActive,
                    allowLocationOnly = false
                )
            }
            else -> {
                val started = promoteToForeground(
                    includeMediaProjection = isMediaProjectionActive,
                    allowWifiWithoutBt = isWifiMirrorActive,
                    allowLocationOnly = isNavLocationActive
                )
                if (!started) {
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun isBleIdleForService(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBtConnect = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBtConnect) return true
        }
        val state = BajajBleManager.getInstance(this).connectionState.value
        return state is BleConnectionState.Disconnected || state is BleConnectionState.Error
    }

    private fun hasLocationPermission(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    private fun hasBtConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun promoteMinimalBeforeAbort() {
        if (isRunning) return
        try {
            createNotificationChannel()
            val notification = buildNotification(
                "Pulsar Navigation",
                "Stopping background service"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val safeType = when {
                    hasBtConnectPermission() -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    hasLocationPermission() -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    else -> return
                }
                startForeground(NOTIFICATION_ID, notification, safeType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Best-effort foreground promotion before immediate stopSelf().
        }
    }

    internal fun dropMediaProjectionInternal(keepWifi: Boolean) {
        isMediaProjectionActive = false
        isWifiMirrorActive = keepWifi
        updateWifiLock(keepWifi)
        if (!keepWifi && !isNavLocationActive && isBleIdleForService()) {
            promoteMinimalBeforeAbort()
            isRunning = false
            stopSelf()
            return
        }
        promoteToForeground(
            includeMediaProjection = false,
            allowWifiWithoutBt = keepWifi,
            allowLocationOnly = isNavLocationActive
        )
    }

    private fun promoteToForeground(
        includeMediaProjection: Boolean = false,
        allowWifiWithoutBt: Boolean = false,
        allowLocationOnly: Boolean = false
    ): Boolean {
        val wasAlreadyRunning = isRunning
        val hasBtConnect = hasBtConnectPermission()
        val hasLocation = hasLocationPermission()
        val canPromoteForLocation = allowLocationOnly && hasLocation
        if (!hasBtConnect && !allowWifiWithoutBt && !includeMediaProjection && !canPromoteForLocation) {
            Log.w(TAG, "Aborting promoteToForeground: neither BLUETOOTH_CONNECT nor active Wi-Fi/Location FGS permission.")
            if (!wasAlreadyRunning) {
                promoteMinimalBeforeAbort()
                isRunning = false
                stopSelf()
            }
            return false
        }
        try {
            createNotificationChannel()
            val notifContent = when {
                includeMediaProjection -> "Casting live screen & navigation over TFT Wi-Fi"
                allowLocationOnly && !allowWifiWithoutBt && !hasBtConnect ->
                    "Tracking GPS route & turn-by-turn navigation"
                else -> "Maintaining cluster Bluetooth & TFT Wi-Fi link"
            }
            val notification = buildNotification(
                "Pulsar Navigation Active",
                notifContent
            )
            val manager = getSystemService(NotificationManager::class.java)

            // CRITICAL: When the service is ALREADY active in foreground (e.g. dropping media projection
            // on screen lock), updating via NotificationManager is 100% safe from the background / screen-off state.
            // On Android 14+ (API 34), calling startForeground() from the background throws
            // ForegroundServiceStartNotAllowedException if types are changed. Updating the notification via
            // NotificationManager preserves the existing foreground service and keeps the peer-to-peer Wi-Fi alive.
            if (wasAlreadyRunning && !includeMediaProjection) {
                try {
                    manager?.notify(NOTIFICATION_ID, notification)
                } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        var desiredTypes = 0
                        if (hasBtConnect || allowWifiWithoutBt) {
                            desiredTypes = desiredTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        }
                        if (hasLocation) {
                            desiredTypes = desiredTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        }
                        if (desiredTypes != 0) {
                            startForeground(NOTIFICATION_ID, notification, desiredTypes)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Screen-off/background FGS type update skipped (foreground status preserved): ${e.message}")
                    }
                }
                return true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var desiredTypes = 0
                if (hasBtConnect || allowWifiWithoutBt) {
                    desiredTypes = desiredTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                if (hasLocation) {
                    desiredTypes = desiredTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (includeMediaProjection) {
                    desiredTypes = desiredTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                if (desiredTypes == 0) {
                    if (!wasAlreadyRunning) {
                        isRunning = false
                        stopSelf()
                    }
                    return false
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, desiredTypes)
                } catch (sec: SecurityException) {
                    var fallbackTypes = 0
                    if (hasBtConnect || allowWifiWithoutBt) {
                        fallbackTypes = fallbackTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    } else if (hasLocation) {
                        fallbackTypes = fallbackTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
                    if (includeMediaProjection) {
                        fallbackTypes = fallbackTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    if (fallbackTypes != 0 && desiredTypes != fallbackTypes) {
                        Log.w(TAG, "Optional FGS type unavailable; falling back to $fallbackTypes: ${sec.message}")
                        startForeground(NOTIFICATION_ID, notification, fallbackTypes)
                    } else {
                        throw sec
                    }
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
            Log.d(
                TAG,
                "BikeBleForegroundService promoted to foreground (mediaProjection=$includeMediaProjection, wifi=$allowWifiWithoutBt, locOnly=$allowLocationOnly)"
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote service to foreground: ${e.message}", e)
            if (!wasAlreadyRunning) {
                isRunning = false
                // CRITICAL: If initial startForeground fails on Android 12+, stopSelf immediately
                // to fulfill OS contract and avoid ForegroundServiceDidNotStartInTimeException
                stopSelf()
                return false
            } else {
                // If service was already running in foreground, do NOT kill it!
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification("Pulsar Navigation Active", "Maintaining cluster connection"))
                } catch (_: Exception) {}
                return true
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        isWifiMirrorActive = false
        isMediaProjectionActive = false
        isNavLocationActive = false
        if (isScreenLockReceiverRegistered) {
            try {
                unregisterReceiver(screenLockReceiver)
            } catch (_: Exception) {}
            isScreenLockReceiverRegistered = false
        }
        try {
            TftWifiMirrorManager.getInstance(this).stopMirroring()
        } catch (_: Exception) {}
        try {
            updateWifiLock(false)
        } catch (_: Exception) {}
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
        Log.d(TAG, "BikeBleForegroundService destroyed")
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pulsar Navigation Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Bluetooth connection active with Bajaj Pulsar cluster"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(com.pulsarnav.app.R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

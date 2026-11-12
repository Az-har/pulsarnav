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
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val EXTRA_KEEP_WIFI_MIRROR = "extra_keep_wifi_mirror"

        @Volatile
        private var isRunning = false

        @Volatile
        private var isWifiMirrorActive = false

        @Volatile
        private var isMediaProjectionActive = false

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

        fun dropMediaProjection(context: Context, keepWifiMirror: Boolean = true) {
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

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        initCallManagerIfPermitted()
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
                val lock = wifiLock ?: wm?.createWifiLock(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    },
                    "PulsarNav:TftSoftApWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                }
                wifiLock = lock
                if (lock != null && !lock.isHeld) {
                    lock.acquire()
                    Log.d(TAG, "High-performance WifiLock acquired for screen-off TFT SoftAP streaming")
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
                isMediaProjectionActive = false
                isWifiMirrorActive = keepWifi
                updateWifiLock(keepWifi)
                if (!keepWifi && isBleIdleForService()) {
                    promoteMinimalBeforeAbort()
                    isRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                promoteToForeground(includeMediaProjection = false, allowWifiWithoutBt = keepWifi)
            }
            ACTION_STOP_TFT_MIRROR -> {
                isWifiMirrorActive = false
                isMediaProjectionActive = false
                updateWifiLock(false)
                TftWifiMirrorManager.getInstance(this).stopMirroring()
                if (isBleIdleForService()) {
                    promoteMinimalBeforeAbort()
                    isRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                promoteToForeground(includeMediaProjection = false, allowWifiWithoutBt = false)
            }
            else -> {
                val started = promoteToForeground(
                    includeMediaProjection = isMediaProjectionActive,
                    allowWifiWithoutBt = isWifiMirrorActive
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

    private fun promoteMinimalBeforeAbort() {
        if (isRunning) return
        try {
            createNotificationChannel()
            val notification = buildNotification(
                "Pulsar Navigation",
                "Stopping background service"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Best-effort foreground promotion before immediate stopSelf().
        }
    }

    private fun promoteToForeground(
        includeMediaProjection: Boolean = false,
        allowWifiWithoutBt: Boolean = false
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !allowWifiWithoutBt && !includeMediaProjection) {
            val hasBtConnect = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBtConnect) {
                Log.w(TAG, "Aborting promoteToForeground: BLUETOOTH_CONNECT permission not granted.")
                promoteMinimalBeforeAbort()
                isRunning = false
                stopSelf()
                return false
            }
        }
        try {
            createNotificationChannel()
            val notifContent = if (includeMediaProjection) {
                "Casting live screen & navigation over TFT Wi-Fi"
            } else {
                "Maintaining cluster Bluetooth & TFT Wi-Fi link"
            }
            val notification = buildNotification(
                "Pulsar Navigation Active",
                notifContent
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasFineLocation = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasCoarseLocation = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                var desiredTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (hasFineLocation || hasCoarseLocation) {
                    desiredTypes = desiredTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (includeMediaProjection) {
                    desiredTypes = desiredTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, desiredTypes)
                } catch (sec: SecurityException) {
                    val fallbackTypes = if (includeMediaProjection) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    }
                    if (desiredTypes != fallbackTypes) {
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
            Log.d(TAG, "BikeBleForegroundService promoted to foreground (mediaProjection=$includeMediaProjection, wifi=$allowWifiWithoutBt)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote service to foreground: ${e.message}", e)
            isRunning = false
            // CRITICAL: If startForeground fails on Android 12+, stopSelf immediately
            // to fulfill OS contract and avoid ForegroundServiceDidNotStartInTimeException
            stopSelf()
            return false
        }
    }

    override fun onDestroy() {
        isRunning = false
        isWifiMirrorActive = false
        isMediaProjectionActive = false
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

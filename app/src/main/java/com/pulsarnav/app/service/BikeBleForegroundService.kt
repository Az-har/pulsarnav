package com.pulsarnav.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pulsarnav.app.ui.MainActivity

class BikeBleForegroundService : Service {

    constructor() : super()

    companion object {
        private const val TAG = "BikeBleForegroundService"
        const val CHANNEL_ID = "pulsar_nav_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        private var isRunning = false

        fun isRunning(): Boolean = isRunning

        fun start(context: Context) {
            if (isRunning) {
                Log.d(TAG, "BikeBleForegroundService is already running; skipping start request.")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasBtConnect = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasBtConnect) {
                    Log.w(TAG, "Cannot start BikeBleForegroundService: BLUETOOTH_CONNECT permission not granted.")
                    return
                }
            }
            try {
                val intent = Intent(context, BikeBleForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BikeBleForegroundService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, BikeBleForegroundService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop BikeBleForegroundService: ${e.message}", e)
            }
            isRunning = false
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        initCallManagerIfPermitted()
        promoteToForeground()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PulsarNav:BleKeepAliveWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12-hour safe upper bound
            }
            Log.d(TAG, "Partial WakeLock acquired for persistent background BLE link")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
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
        promoteToForeground()
        return START_STICKY
    }

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBtConnect = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasBtConnect) {
                Log.w(TAG, "Aborting promoteToForeground: BLUETOOTH_CONNECT permission not granted.")
                isRunning = false
                stopSelf()
                return
            }
        }
        try {
            createNotificationChannel()
            val notification = buildNotification(
                "Pulsar Navigation Active",
                "Maintaining Bluetooth link with bike cluster"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (hasLocation) {
                        fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    fgsType
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
            Log.d(TAG, "BikeBleForegroundService successfully promoted to foreground")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote service to foreground: ${e.message}", e)
            isRunning = false
            // CRITICAL: If startForeground fails on Android 12+, stopSelf immediately
            // to fulfill OS contract and avoid ForegroundServiceDidNotStartInTimeException
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

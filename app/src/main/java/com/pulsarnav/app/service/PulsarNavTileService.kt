package com.pulsarnav.app.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.pulsarnav.app.R
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.ble.BleConnectionState
import com.pulsarnav.app.ui.MainActivity
import com.pulsarnav.app.wifi.TftMirrorStatus
import com.pulsarnav.app.wifi.TftWifiMirrorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class PulsarNavTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val wifiMgr = TftWifiMirrorManager.getInstance(this)
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            wifiMgr.mirrorState.collect { mirror ->
                updateTile(mirror.status)
            }
        }
    }

    override fun onStopListening() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val wifiMgr = TftWifiMirrorManager.getInstance(this)
        val currentStatus = wifiMgr.mirrorState.value.status
        val isStreaming = currentStatus == TftMirrorStatus.STREAMING ||
            currentStatus == TftMirrorStatus.CONNECTING_WIFI ||
            currentStatus == TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP

        if (isStreaming) {
            BikeBleForegroundService.stopTftMirror(this)
        } else {
            val bleState = BajajBleManager.getInstance(this).connectionState.value
            if (bleState is BleConnectionState.Connected) {
                BikeBleForegroundService.startForTftCockpitMirror(this)
            } else {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
    }

    private fun updateTile(status: TftMirrorStatus) {
        val tile = qsTile ?: return
        when (status) {
            TftMirrorStatus.STREAMING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "TFT Mirroring"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Streaming 800×480"
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher)
            }
            TftMirrorStatus.CONNECTING_WIFI, TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "TFT Connecting"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Joining SoftAP..."
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher)
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Cast to Bike"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to Mirror HUD"
                }
                tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher)
            }
        }
        tile.updateTile()
    }
}

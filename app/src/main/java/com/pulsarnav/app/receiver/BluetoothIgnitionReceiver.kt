package com.pulsarnav.app.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.service.BikeBleForegroundService

/**
 * Automates motorcycle connection lifecycle when the bike's ignition turns ON or OFF.
 */
class BluetoothIgnitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IgnitionReceiver"
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            return
        }
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        val canReadBt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!canReadBt) return

        val deviceName = try { device.name } catch (_: Exception) { null } ?: ""
        val deviceAddress = device.address ?: ""

        Log.d(TAG, "Event: $action | Device: '$deviceName' [$deviceAddress]")

        val isBike = isBikeDevice(deviceName, deviceAddress, context)
        if (!isBike) return

        val settings = AppSettings.getInstance(context)
        val bleManager = BajajBleManager.getInstance(context)

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                bleManager.onSystemAclConnected()
                if (!settings.isAutoConnectEnabled) {
                    Log.d(TAG, "Auto-connect disabled by user; ignoring ACL_CONNECTED for '$deviceName'")
                    return
                }
                Log.i(TAG, "Cluster Ignition ON ($deviceName)! Starting Keep-Alive BLE Service & Auto-Connect...")
                BikeBleForegroundService.start(context)
                bleManager.startAutoConnect(force = true)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "Cluster ACL_DISCONNECTED ($deviceName) — scheduling debounced trip/parking check...")
                bleManager.onSystemAclDisconnected()
            }
        }
    }

    private fun isBikeDevice(name: String, address: String, context: Context): Boolean {
        val lower = name.lowercase()
        if (lower.contains("bajaj") || lower.contains("pulsar") || lower.contains("ns400") || lower.contains("ns200") || lower.contains("cluster")) {
            return true
        }
        val savedMac = AppSettings.getInstance(context).lastConnectedBikeMac
        if (savedMac.isNotBlank() && address.equals(savedMac, ignoreCase = true)) {
            return true
        }
        return false
    }
}

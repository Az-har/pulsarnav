package com.pulsarnav.app.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.location.ParkingManager
import com.pulsarnav.app.service.BikeBleForegroundService
import com.pulsarnav.app.tracking.RideTracker

/**
 * Automates motorcycle connection lifecycle when the bike's ignition turns ON or OFF.
 * Works seamlessly with Samsung Modes & Routines and standard Android Bluetooth events.
 */
class BluetoothIgnitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IgnitionReceiver"
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val deviceName = try { device?.name } catch (_: Exception) { null } ?: ""
        val deviceAddress = device?.address ?: ""

        Log.d(TAG, "Event: $action | Device: '$deviceName' [$deviceAddress]")

        val isBike = isBikeDevice(deviceName, deviceAddress, context)

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (isBike) {
                    Log.i(TAG, "Cluster Ignition ON ($deviceName)! Starting Keep-Alive BLE Service & Auto-Connect...")
                    BikeBleForegroundService.start(context)
                    com.pulsarnav.app.ble.BajajBleManager.getInstance(context).startAutoConnect(force = true)
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (isBike) {
                    Log.i(TAG, "Cluster Ignition OFF ($deviceName)! Auto-saving parking location and closing trip...")
                    ParkingManager.getInstance(context).onBikeDisconnected()
                    val finishedTrip = RideTracker.getInstance(context).onBikeDisconnected()
                    if (finishedTrip != null && finishedTrip.distanceKm > 0.0) {
                        com.pulsarnav.app.maintenance.MaintenanceManager.getInstance(context)
                            .addDistance(finishedTrip.distanceKm)
                    }
                }
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

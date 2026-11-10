package com.pulsarnav.app.maintenance

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pulsarnav.app.R
import com.pulsarnav.app.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServiceRecord(
    val id: String = UUID.randomUUID().toString(),
    var date: String,
    var odoKm: Double,
    var title: String,
    var workshop: String = "Bajaj Authorized Service",
    var notes: String = "",
    var cost: String = "₹0"
)

data class MaintenanceStatus(
    val totalOdometerKm: Double,
    val chainLubeRemainingKm: Double,
    val chainLubePercent: Int,
    val serviceRemainingKm: Double,
    val servicePercent: Int,
    val nextServiceTargetOdoKm: Double,
    val isChainLubeDue: Boolean,
    val isServiceDue: Boolean,
    val isServiceOverdue: Boolean,
    val serviceOverdueKm: Double
)

class MaintenanceManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_maintenance", Context.MODE_PRIVATE)

    companion object {
        const val CHAIN_LUBE_INTERVAL_KM = 500.0
        const val SERVICE_INTERVAL_KM = 5000.0

        private const val KEY_ODO = "odo_total_km"
        private const val KEY_LAST_CHAIN_LUBE = "last_chain_lube_km"
        private const val KEY_LAST_SERVICE = "last_service_km"
        private const val KEY_NEXT_SERVICE_TARGET_ODO = "next_service_target_odo_km"
        private const val KEY_SERVICE_HISTORY_JSON = "service_history_json"

        @Volatile
        private var instance: MaintenanceManager? = null

        fun getInstance(context: Context): MaintenanceManager {
            return instance ?: synchronized(this) {
                instance ?: MaintenanceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _status = MutableStateFlow(computeStatus())
    val status: StateFlow<MaintenanceStatus> = _status.asStateFlow()

    private fun computeStatus(): MaintenanceStatus {
        val odo = prefs.getString(KEY_ODO, "0.0")?.toDoubleOrNull() ?: 0.0
        val lastLube = prefs.getString(KEY_LAST_CHAIN_LUBE, "0.0")?.toDoubleOrNull() ?: 0.0

        val lubeElapsed = (odo - lastLube).coerceAtLeast(0.0)
        val lubeRemaining = (CHAIN_LUBE_INTERVAL_KM - lubeElapsed).coerceAtLeast(0.0)
        val lubePercent = ((lubeRemaining / CHAIN_LUBE_INTERVAL_KM) * 100).toInt().coerceIn(0, 100)

        val rawTarget = prefs.getString(KEY_NEXT_SERVICE_TARGET_ODO, null)?.toDoubleOrNull()
        val targetOdo = rawTarget ?: when {
            odo < 750.0 -> 500.0 // 1st Service milestone for new motorcycle
            odo < 5000.0 -> 5000.0 // 2nd Service
            odo < 10000.0 -> 10000.0 // 3rd Service
            else -> ((odo / 5000.0).toInt() + 1) * 5000.0
        }

        val isOverdue = odo > targetOdo
        val overdueKm = if (isOverdue) (odo - targetOdo) else 0.0
        val serviceRemaining = if (isOverdue) 0.0 else (targetOdo - odo)
        val serviceInterval = if (targetOdo <= 750.0) 500.0 else 5000.0
        val servicePercent = if (isOverdue) 0 else ((serviceRemaining / serviceInterval) * 100).toInt().coerceIn(0, 100)

        return MaintenanceStatus(
            totalOdometerKm = odo,
            chainLubeRemainingKm = lubeRemaining,
            chainLubePercent = lubePercent,
            serviceRemainingKm = serviceRemaining,
            servicePercent = servicePercent,
            nextServiceTargetOdoKm = targetOdo,
            isChainLubeDue = lubeRemaining <= 50.0,
            isServiceDue = isOverdue || serviceRemaining <= 200.0,
            isServiceOverdue = isOverdue,
            serviceOverdueKm = overdueKm
        )
    }

    fun setNextServiceTargetOdo(targetKm: Double) {
        prefs.edit().putString(KEY_NEXT_SERVICE_TARGET_ODO, "%.1f".format(targetKm)).apply()
        _status.value = computeStatus()
    }

    fun getLastChainLubeKm(): Double = prefs.getString(KEY_LAST_CHAIN_LUBE, "0.0")?.toDoubleOrNull() ?: 0.0
    fun getLastServiceKm(): Double = prefs.getString(KEY_LAST_SERVICE, "0.0")?.toDoubleOrNull() ?: 0.0
    fun getNextServiceTargetOdoKm(): Double = prefs.getString(KEY_NEXT_SERVICE_TARGET_ODO, "500.0")?.toDoubleOrNull() ?: 500.0

    fun setLastChainLubeKm(km: Double) {
        prefs.edit().putString(KEY_LAST_CHAIN_LUBE, "%.1f".format(km)).apply()
        _status.value = computeStatus()
    }

    fun setLastServiceKm(km: Double) {
        prefs.edit().putString(KEY_LAST_SERVICE, "%.1f".format(km)).apply()
        _status.value = computeStatus()
    }

    fun rescheduleNextService(addKm: Double) {
        val odo = prefs.getString(KEY_ODO, "0.0")?.toDoubleOrNull() ?: 0.0
        setNextServiceTargetOdo(odo + addKm)
    }

    fun addDistance(km: Double) {
        val currentOdo = prefs.getString(KEY_ODO, "0.0")?.toDoubleOrNull() ?: 0.0
        val newOdo = currentOdo + km
        prefs.edit().putString(KEY_ODO, "%.1f".format(newOdo)).apply()
        _status.value = computeStatus()
        checkAndSendMaintenanceReminders()
    }

    fun setOdometer(newOdo: Double) {
        prefs.edit().putString(KEY_ODO, "%.1f".format(newOdo)).apply()
        _status.value = computeStatus()
        checkAndSendMaintenanceReminders()
    }

    fun markChainLubed() {
        val currentOdo = prefs.getString(KEY_ODO, "0.0")?.toDoubleOrNull() ?: 0.0
        prefs.edit().putString(KEY_LAST_CHAIN_LUBE, currentOdo.toString()).apply()
        _status.value = computeStatus()
        checkAndSendMaintenanceReminders()
    }

    fun markServiceDone() {
        val currentOdo = prefs.getString(KEY_ODO, "0.0")?.toDoubleOrNull() ?: 0.0
        prefs.edit().putString(KEY_LAST_SERVICE, currentOdo.toString()).apply()

        // Advance to next milestone
        val currentTarget = prefs.getString(KEY_NEXT_SERVICE_TARGET_ODO, null)?.toDoubleOrNull() ?: 500.0
        val nextTarget = when {
            currentTarget <= 750.0 -> 5000.0 // 1st service done -> 2nd is 5,000 km
            currentTarget <= 5000.0 -> 10000.0 // 2nd service done -> 3rd is 10,000 km
            else -> currentOdo + 5000.0
        }
        prefs.edit().putString(KEY_NEXT_SERVICE_TARGET_ODO, "%.1f".format(nextTarget)).apply()
        _status.value = computeStatus()
        checkAndSendMaintenanceReminders()
    }

    fun checkAndSendMaintenanceReminders() {
        try {
            val currentStatus = _status.value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "pulsarnav_maintenance_channel",
                    "Maintenance & Service Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts for upcoming chain lube and scheduled periodic maintenance"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val alertPrefs = context.getSharedPreferences("pulsarnav_maintenance_alerts", Context.MODE_PRIVATE)

            // 1. Chain Lube Reminder (1..100 km threshold)
            if (currentStatus.chainLubeRemainingKm in 1.0..100.0) {
                val lubeMilestone = (currentStatus.totalOdometerKm / 500.0).toInt()
                val lastMilestone = alertPrefs.getInt("last_chain_milestone", -1)
                if (lubeMilestone != lastMilestone) {
                    val notif = NotificationCompat.Builder(context, "pulsarnav_maintenance_channel")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Chain Lube Due Soon")
                        .setContentText("Only %.0f km remaining before recommended chain cleaning & lube.".format(currentStatus.chainLubeRemainingKm))
                        .setStyle(NotificationCompat.BigTextStyle().bigText("Your motorcycle drive chain is due for lubrication in %.0f km (Current ODO: %.1f km). Regular chain care prevents sprocket wear.".format(currentStatus.chainLubeRemainingKm, currentStatus.totalOdometerKm)))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(2001, notif)
                    alertPrefs.edit().putInt("last_chain_milestone", lubeMilestone).apply()
                }
            } else if (currentStatus.chainLubeRemainingKm <= 0.0) {
                val lubeMilestone = (currentStatus.totalOdometerKm / 500.0).toInt()
                val lastMilestone = alertPrefs.getInt("last_chain_overdue_milestone", -1)
                if (lubeMilestone != lastMilestone) {
                    val notif = NotificationCompat.Builder(context, "pulsarnav_maintenance_channel")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Chain Lubrication OVERDUE")
                        .setContentText("Chain lube interval has been exceeded (%.1f km ODO).".format(currentStatus.totalOdometerKm))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(2001, notif)
                    alertPrefs.edit().putInt("last_chain_overdue_milestone", lubeMilestone).apply()
                }
            }

            // 2. Periodic Service Reminder (1..300 km threshold)
            val targetOdo = currentStatus.nextServiceTargetOdoKm
            if (currentStatus.serviceRemainingKm in 1.0..300.0) {
                val lastNotifiedTarget = alertPrefs.getFloat("last_notified_service_target", -1f)
                if (Math.abs(lastNotifiedTarget.toDouble() - targetOdo) > 5.0) {
                    val notif = NotificationCompat.Builder(context, "pulsarnav_maintenance_channel")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Periodic Service Approaching")
                        .setContentText("%.0f km remaining until scheduled service target (%.0f km).".format(currentStatus.serviceRemainingKm, targetOdo))
                        .setStyle(NotificationCompat.BigTextStyle().bigText("Motorcycle scheduled service is due at %.0f km (%.0f km remaining). Prepare to inspect engine oil, filters, spark plug, and brake pads.".format(targetOdo, currentStatus.serviceRemainingKm)))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(2002, notif)
                    alertPrefs.edit().putFloat("last_notified_service_target", targetOdo.toFloat()).apply()
                }
            } else if (currentStatus.isServiceOverdue) {
                val overdueKey = "overdue_notified_${targetOdo.toInt()}"
                if (!alertPrefs.getBoolean(overdueKey, false)) {
                    val notif = NotificationCompat.Builder(context, "pulsarnav_maintenance_channel")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Periodic Service OVERDUE")
                        .setContentText("Service milestone (%.0f km) is overdue by %.0f km.".format(targetOdo, currentStatus.serviceOverdueKm))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(2002, notif)
                    alertPrefs.edit().putBoolean(overdueKey, true).apply()
                }
            }
        } catch (_: Exception) {}
    }

    fun getServiceHistory(): MutableList<ServiceRecord> {
        val raw = prefs.getString(KEY_SERVICE_HISTORY_JSON, null)
        if (raw.isNullOrBlank()) {
            return mutableListOf()
        }

        val list = mutableListOf<ServiceRecord>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ServiceRecord(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        date = obj.optString("date", "Today"),
                        odoKm = obj.optDouble("odoKm", 0.0),
                        title = obj.optString("title", "Periodic Service"),
                        workshop = obj.optString("workshop", "Bajaj Service"),
                        notes = obj.optString("notes", ""),
                        cost = obj.optString("cost", "₹0")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveServiceHistory(records: List<ServiceRecord>) {
        val array = JSONArray()
        for (rec in records) {
            val obj = JSONObject()
            obj.put("id", rec.id)
            obj.put("date", rec.date)
            obj.put("odoKm", rec.odoKm)
            obj.put("title", rec.title)
            obj.put("workshop", rec.workshop)
            obj.put("notes", rec.notes)
            obj.put("cost", rec.cost)
            array.put(obj)
        }
        prefs.edit().putString(KEY_SERVICE_HISTORY_JSON, array.toString()).apply()
        // Check if latest service can update last_service_km
        val maxServiceOdo = records.maxOfOrNull { it.odoKm } ?: 0.0
        val currentLastService = prefs.getString(KEY_LAST_SERVICE, "0.0")?.toDoubleOrNull() ?: 0.0
        if (maxServiceOdo > currentLastService) {
            prefs.edit().putString(KEY_LAST_SERVICE, maxServiceOdo.toString()).apply()
        }
        _status.value = computeStatus()
    }

    fun addServiceRecord(record: ServiceRecord) {
        val current = getServiceHistory()
        current.add(0, record)
        saveServiceHistory(current)
    }

    fun updateServiceRecord(record: ServiceRecord) {
        val current = getServiceHistory()
        val index = current.indexOfFirst { it.id == record.id }
        if (index != -1) {
            current[index] = record
            saveServiceHistory(current)
        }
    }

    fun deleteServiceRecord(id: String) {
        val current = getServiceHistory()
        current.removeAll { it.id == id }
        saveServiceHistory(current)
    }
}

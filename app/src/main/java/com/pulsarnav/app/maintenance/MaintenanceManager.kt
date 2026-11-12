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
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

internal enum class GloveboxDocumentStatus {
    NO_FILE,
    ATTACHED_NO_EXPIRY,
    VALID,
    EXPIRING_SOON,
    EXPIRED
}

internal object GloveboxExpiryEvaluator {
    private const val EXPIRING_SOON_DAYS = 30L

    private val FULL_DATE_FORMATTERS = listOf(
        "dd MMM yyyy",
        "d MMM yyyy",
        "dd MMMM yyyy",
        "d MMMM yyyy",
        "dd/MM/yyyy",
        "d/M/yyyy",
        "dd-MM-yyyy",
        "d-M-yyyy",
        "yyyy-MM-dd",
        "yyyy/MM/dd"
    ).map { pattern ->
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .toFormatter(Locale.ENGLISH)
    }

    private val MONTH_YEAR_FORMATTERS = listOf(
        "MMM yyyy",
        "MMMM yyyy",
        "MM/yyyy",
        "M/yyyy",
        "MM-yyyy",
        "yyyy-MM"
    ).map { pattern ->
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .toFormatter(Locale.ENGLISH)
    }

    fun parseExpiryDate(rawExpiry: String?): LocalDate? {
        val trimmed = rawExpiry?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (trimmed.isEmpty()) return null
        for (fmt in FULL_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(trimmed, fmt)
            } catch (_: Exception) {
            }
        }
        for (fmt in MONTH_YEAR_FORMATTERS) {
            try {
                return YearMonth.parse(trimmed, fmt).atEndOfMonth()
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun evaluate(
        hasFile: Boolean,
        rawExpiry: String?,
        today: LocalDate = LocalDate.now()
    ): GloveboxDocumentStatus {
        val expiryDate = parseExpiryDate(rawExpiry)
        return when {
            expiryDate != null -> {
                val daysRemaining = ChronoUnit.DAYS.between(today, expiryDate)
                when {
                    daysRemaining < 0L -> GloveboxDocumentStatus.EXPIRED
                    daysRemaining <= EXPIRING_SOON_DAYS -> GloveboxDocumentStatus.EXPIRING_SOON
                    hasFile -> GloveboxDocumentStatus.VALID
                    else -> GloveboxDocumentStatus.NO_FILE
                }
            }
            !hasFile -> GloveboxDocumentStatus.NO_FILE
            else -> GloveboxDocumentStatus.ATTACHED_NO_EXPIRY
        }
    }
}

internal object MaintenanceMath {
    fun formatDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    fun formatCurrencyAmount(amount: Double): String {
        val rounded = kotlin.math.round(amount * 100.0) / 100.0
        return if (kotlin.math.abs(rounded - kotlin.math.round(rounded)) < 0.005) {
            String.format(Locale.US, "%.0f", rounded)
        } else {
            String.format(Locale.US, "%.2f", rounded)
        }
    }

    fun defaultServiceTargetOdo(odoKm: Double): Double = when {
        odoKm < 750.0 -> 500.0 // 1st Service milestone for new motorcycle
        odoKm < 5000.0 -> 5000.0 // 2nd Service
        odoKm < 10000.0 -> 10000.0 // 3rd Service
        else -> ((odoKm / 5000.0).toInt() + 1) * 5000.0
    }

    fun parseDecimal(raw: String?): Double? =
        raw?.trim()?.replace(',', '.')?.toDoubleOrNull()

    fun parseCurrencyAmount(raw: String?): Double? {
        val cleaned = raw?.replace(Regex("[^0-9.,]"), "").orEmpty()
        if (cleaned.isEmpty()) return null
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> {
                if (lastComma > lastDot) {
                    cleaned.replace(".", "").replace(',', '.')
                } else {
                    cleaned.replace(",", "")
                }
            }
            lastComma >= 0 -> {
                val firstComma = cleaned.indexOf(',')
                val fractionalDigits = cleaned.length - lastComma - 1
                if (firstComma != lastComma || fractionalDigits == 3) {
                    cleaned.replace(",", "")
                } else {
                    cleaned.replace(',', '.')
                }
            }
            else -> {
                val firstDot = cleaned.indexOf('.')
                if (firstDot != lastDot) cleaned.replace(".", "") else cleaned
            }
        }
        return normalized.toDoubleOrNull()
    }
}

data class ServiceItem(
    val name: String,
    val cost: Double
)

data class ServiceRecord(
    val id: String = UUID.randomUUID().toString(),
    var date: String,
    var odoKm: Double,
    var title: String,
    var workshop: String = "Self / Workshop",
    var notes: String = "",
    var cost: String = "₹0",
    var items: List<ServiceItem> = emptyList()
) {
    fun getNumericCost(): Double {
        val parsedCost = MaintenanceMath.parseCurrencyAmount(cost) ?: 0.0
        val itemsSum = items.sumOf { it.cost }
        return if (parsedCost > 0.0) parsedCost else itemsSum
    }
}

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
        val odo = MaintenanceMath.parseDecimal(prefs.getString(KEY_ODO, "0.0")) ?: 0.0
        val lastLube = MaintenanceMath.parseDecimal(prefs.getString(KEY_LAST_CHAIN_LUBE, "0.0")) ?: 0.0

        val lubeElapsed = (odo - lastLube).coerceAtLeast(0.0)
        val lubeRemaining = (CHAIN_LUBE_INTERVAL_KM - lubeElapsed).coerceAtLeast(0.0)
        val lubePercent = ((lubeRemaining / CHAIN_LUBE_INTERVAL_KM) * 100).toInt().coerceIn(0, 100)

        val rawTarget = MaintenanceMath.parseDecimal(prefs.getString(KEY_NEXT_SERVICE_TARGET_ODO, null))
        val targetOdo = rawTarget ?: MaintenanceMath.defaultServiceTargetOdo(odo)

        val isOverdue = odo > targetOdo
        val overdueKm = if (isOverdue) (odo - targetOdo) else 0.0
        val serviceRemaining = if (isOverdue) 0.0 else (targetOdo - odo)
        val serviceInterval = if (targetOdo <= 750.0) 500.0 else SERVICE_INTERVAL_KM
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
        prefs.edit().putString(KEY_NEXT_SERVICE_TARGET_ODO, MaintenanceMath.formatDecimal(targetKm)).apply()
        _status.value = computeStatus()
    }

    fun getLastChainLubeKm(): Double =
        MaintenanceMath.parseDecimal(prefs.getString(KEY_LAST_CHAIN_LUBE, "0.0")) ?: 0.0

    fun getLastServiceKm(): Double =
        MaintenanceMath.parseDecimal(prefs.getString(KEY_LAST_SERVICE, "0.0")) ?: 0.0

    fun getNextServiceTargetOdoKm(): Double =
        _status.value.nextServiceTargetOdoKm

    fun setLastChainLubeKm(km: Double) {
        prefs.edit().putString(KEY_LAST_CHAIN_LUBE, MaintenanceMath.formatDecimal(km)).apply()
        _status.value = computeStatus()
    }

    fun setLastServiceKm(km: Double) {
        prefs.edit().putString(KEY_LAST_SERVICE, MaintenanceMath.formatDecimal(km)).apply()
        _status.value = computeStatus()
    }

    fun rescheduleNextService(addKm: Double) {
        val odo = MaintenanceMath.parseDecimal(prefs.getString(KEY_ODO, "0.0")) ?: 0.0
        setNextServiceTargetOdo(odo + addKm)
    }

    fun addDistance(km: Double) {
        val currentOdo = MaintenanceMath.parseDecimal(prefs.getString(KEY_ODO, "0.0")) ?: 0.0
        val newOdo = currentOdo + km
        prefs.edit().putString(KEY_ODO, MaintenanceMath.formatDecimal(newOdo)).apply()
        _status.value = computeStatus()
        checkAndSendMaintenanceReminders()
    }

    fun setOdometer(newOdo: Double) {
        prefs.edit().putString(KEY_ODO, MaintenanceMath.formatDecimal(newOdo)).apply()
        _status.value = computeStatus()
        checkAndSendMaintenanceReminders()
    }

    fun markChainLubed() {
        val currentOdo = MaintenanceMath.parseDecimal(prefs.getString(KEY_ODO, "0.0")) ?: 0.0
        prefs.edit().putString(KEY_LAST_CHAIN_LUBE, MaintenanceMath.formatDecimal(currentOdo)).apply()
        _status.value = computeStatus()
        checkAndSendMaintenanceReminders()
    }

    fun markServiceDone() {
        val currentOdo = MaintenanceMath.parseDecimal(prefs.getString(KEY_ODO, "0.0")) ?: 0.0
        prefs.edit().putString(KEY_LAST_SERVICE, MaintenanceMath.formatDecimal(currentOdo)).apply()

        // Advance to next milestone
        val currentTarget = MaintenanceMath.parseDecimal(prefs.getString(KEY_NEXT_SERVICE_TARGET_ODO, null))
            ?: MaintenanceMath.defaultServiceTargetOdo(currentOdo)
        val nextTarget = when {
            currentTarget <= 750.0 -> 5000.0 // 1st service done -> 2nd is 5,000 km
            currentTarget <= 5000.0 -> 10000.0 // 2nd service done -> 3rd is 10,000 km
            else -> currentOdo + SERVICE_INTERVAL_KM
        }
        prefs.edit().putString(KEY_NEXT_SERVICE_TARGET_ODO, MaintenanceMath.formatDecimal(nextTarget)).apply()
        _status.value = computeStatus()
        checkAndSendMaintenanceReminders()
    }

    fun checkAndSendMaintenanceReminders() {
        if (!AppSettings.getInstance(context).isMaintenanceAlertsEnabled) {
            return
        }
        try {
            val currentStatus = _status.value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val channel = NotificationChannel(
                "pulsarnav_maintenance_channel",
                "Maintenance & Service Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for upcoming chain lube and scheduled periodic maintenance"
            }
            notificationManager.createNotificationChannel(channel)

            val alertPrefs = context.getSharedPreferences("pulsarnav_maintenance_alerts", Context.MODE_PRIVATE)
            val lubeCycleKey = kotlin.math.round(getLastChainLubeKm() * 10.0).toLong()

            // 1. Chain Lube Reminder (1..100 km threshold based on elapsed distance since last lube)
            if (currentStatus.chainLubeRemainingKm in 1.0..100.0) {
                val lastNotifiedCycle = alertPrefs.getLong("last_chain_soon_cycle", Long.MIN_VALUE)
                if (lubeCycleKey != lastNotifiedCycle) {
                    val notif = NotificationCompat.Builder(context, "pulsarnav_maintenance_channel")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Chain Lube Due Soon")
                        .setContentText("Only %.0f km remaining before recommended chain cleaning & lube.".format(currentStatus.chainLubeRemainingKm))
                        .setStyle(NotificationCompat.BigTextStyle().bigText("Your motorcycle drive chain is due for lubrication in %.0f km (Current ODO: %.1f km). Regular chain care prevents sprocket wear.".format(currentStatus.chainLubeRemainingKm, currentStatus.totalOdometerKm)))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(2001, notif)
                    alertPrefs.edit().putLong("last_chain_soon_cycle", lubeCycleKey).apply()
                }
            } else if (currentStatus.chainLubeRemainingKm <= 0.0) {
                val lastOverdueCycle = alertPrefs.getLong("last_chain_overdue_cycle", Long.MIN_VALUE)
                if (lubeCycleKey != lastOverdueCycle) {
                    val notif = NotificationCompat.Builder(context, "pulsarnav_maintenance_channel")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Chain Lubrication OVERDUE")
                        .setContentText("Chain lube interval has been exceeded (%.1f km ODO).".format(currentStatus.totalOdometerKm))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    notificationManager.notify(2001, notif)
                    alertPrefs.edit().putLong("last_chain_overdue_cycle", lubeCycleKey).apply()
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

    fun getTotalServiceCost(): Double {
        return getServiceHistory().sumOf { it.getNumericCost() }
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
                val itemsList = mutableListOf<ServiceItem>()
                val itemsArr = obj.optJSONArray("items")
                if (itemsArr != null) {
                    for (j in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.getJSONObject(j)
                        val name = itemObj.optString("name", "")
                        val cost = itemObj.optDouble("cost", 0.0)
                        if (name.isNotBlank()) {
                            itemsList.add(ServiceItem(name, cost))
                        }
                    }
                }
                list.add(
                    ServiceRecord(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        date = obj.optString("date", "Today"),
                        odoKm = obj.optDouble("odoKm", 0.0),
                        title = obj.optString("title", "Periodic Service"),
                        workshop = obj.optString("workshop", "Self / Workshop"),
                        notes = obj.optString("notes", ""),
                        cost = obj.optString("cost", "₹0"),
                        items = itemsList
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

            val itemsArr = JSONArray()
            for (item in rec.items) {
                val itemObj = JSONObject()
                itemObj.put("name", item.name)
                itemObj.put("cost", item.cost)
                itemsArr.put(itemObj)
            }
            obj.put("items", itemsArr)

            array.put(obj)
        }
        prefs.edit().putString(KEY_SERVICE_HISTORY_JSON, array.toString()).apply()
        val maxServiceOdo = records.maxOfOrNull { it.odoKm } ?: 0.0
        prefs.edit().putString(KEY_LAST_SERVICE, MaintenanceMath.formatDecimal(maxServiceOdo)).apply()
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

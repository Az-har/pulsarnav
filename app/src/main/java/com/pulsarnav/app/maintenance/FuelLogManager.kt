package com.pulsarnav.app.maintenance

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class FuelEntry(
    val id: String,
    val timestampMs: Long,
    val odometerKm: Double,
    val liters: Double,
    val totalCostInr: Double,
    val calculatedKmpl: Double?
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestampMs))
}

data class FuelStats(
    val averageKmpl: Double,
    val totalSpentInr: Double,
    val costPerKm: Double
)

class FuelLogManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_fuel", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FUEL_LIST = "saved_fuel_entries"

        @Volatile
        private var instance: FuelLogManager? = null

        fun getInstance(context: Context): FuelLogManager {
            return instance ?: synchronized(this) {
                instance ?: FuelLogManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _entries = MutableStateFlow<List<FuelEntry>>(loadEntries())
    val entries: StateFlow<List<FuelEntry>> = _entries.asStateFlow()

    private val _stats = MutableStateFlow(computeStats(_entries.value))
    val stats: StateFlow<FuelStats> = _stats.asStateFlow()

    fun addFuelEntry(odometerKm: Double, liters: Double, totalCostInr: Double): FuelEntry {
        val entry = FuelEntry(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            odometerKm = odometerKm,
            liters = liters,
            totalCostInr = totalCostInr,
            calculatedKmpl = null
        )

        val current = _entries.value.toMutableList()
        current.add(0, entry)
        val recalculated = recalculateChainedStats(current)
        _entries.value = recalculated
        _stats.value = computeStats(recalculated)
        saveEntries(recalculated)

        MaintenanceManager.getInstance(context).setOdometer(odometerKm)
        return recalculated.firstOrNull { it.id == entry.id } ?: entry
    }

    fun updateFuelEntry(id: String, odometerKm: Double, liters: Double, totalCostInr: Double): FuelEntry? {
        val current = _entries.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return null

        val old = current[index]
        val updated = old.copy(
            odometerKm = odometerKm,
            liters = liters,
            totalCostInr = totalCostInr
        )
        current[index] = updated
        val recalculated = recalculateChainedStats(current)
        _entries.value = recalculated
        _stats.value = computeStats(recalculated)
        saveEntries(recalculated)
        MaintenanceManager.getInstance(context).setOdometer(odometerKm)
        return updated
    }

    fun deleteFuelEntry(id: String): Boolean {
        val current = _entries.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            val recalculated = recalculateChainedStats(current)
            _entries.value = recalculated
            _stats.value = computeStats(recalculated)
            saveEntries(recalculated)
        }
        return removed
    }

    /**
     * Imports an external list of fuel entries (e.g. from Data Vault),
     * preventing duplicates by ID and recalculating mileage stats.
     */
    fun importFuelEntries(importedList: List<FuelEntry>): Int {
        val current = _entries.value.toMutableList()
        val existingIds = current.map { it.id }.toSet()
        var addedCount = 0
        for (item in importedList) {
            if (item.id !in existingIds) {
                current.add(item)
                addedCount++
            }
        }
        if (addedCount > 0) {
            val recalculated = recalculateChainedStats(current)
            _entries.value = recalculated
            _stats.value = computeStats(recalculated)
            saveEntries(recalculated)
        }
        return addedCount
    }

    private fun recalculateChainedStats(list: List<FuelEntry>): List<FuelEntry> {
        val sorted = list.sortedByDescending { it.timestampMs }
        val result = mutableListOf<FuelEntry>()
        for (i in sorted.indices) {
            val item = sorted[i]
            var kmpl: Double? = null
            if (i < sorted.size - 1) {
                val previous = sorted[i + 1]
                val dist = (item.odometerKm - previous.odometerKm).coerceAtLeast(0.0)
                if (item.liters > 0 && dist > 1.0) {
                    kmpl = dist / item.liters
                }
            }
            result.add(item.copy(calculatedKmpl = kmpl))
        }
        return result
    }

    private fun computeStats(list: List<FuelEntry>): FuelStats {
        if (list.isEmpty()) return FuelStats(0.0, 0.0, 0.0)

        var totalSpent = 0.0
        var totalLiters = 0.0
        var validKmplCount = 0
        var kmplSum = 0.0

        for (e in list) {
            totalSpent += e.totalCostInr
            totalLiters += e.liters
            if (e.calculatedKmpl != null && e.calculatedKmpl > 5.0 && e.calculatedKmpl < 70.0) {
                kmplSum += e.calculatedKmpl
                validKmplCount++
            }
        }

        val avgKmpl = if (validKmplCount > 0) kmplSum / validKmplCount else 0.0
        val totalDistance = if (list.size > 1) (list.first().odometerKm - list.last().odometerKm).coerceAtLeast(1.0) else 0.0
        val costPerKm = if (totalDistance > 0) totalSpent / totalDistance else 0.0

        return FuelStats(avgKmpl, totalSpent, costPerKm)
    }

    private fun saveEntries(list: List<FuelEntry>) {
        val array = JSONArray()
        for (e in list) {
            val obj = JSONObject().apply {
                put("id", e.id)
                put("timestampMs", e.timestampMs)
                put("odometerKm", e.odometerKm)
                put("liters", e.liters)
                put("totalCostInr", e.totalCostInr)
                if (e.calculatedKmpl != null) put("calculatedKmpl", e.calculatedKmpl)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_FUEL_LIST, array.toString()).apply()
    }

    private fun loadEntries(): List<FuelEntry> {
        val raw = prefs.getString(KEY_FUEL_LIST, null) ?: return emptyList()
        val list = mutableListOf<FuelEntry>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    FuelEntry(
                        id = obj.getString("id"),
                        timestampMs = obj.getLong("timestampMs"),
                        odometerKm = obj.getDouble("odometerKm"),
                        liters = obj.getDouble("liters"),
                        totalCostInr = obj.getDouble("totalCostInr"),
                        calculatedKmpl = if (obj.has("calculatedKmpl")) obj.getDouble("calculatedKmpl") else null
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }
}

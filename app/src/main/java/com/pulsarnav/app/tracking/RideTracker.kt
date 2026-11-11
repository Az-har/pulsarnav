package com.pulsarnav.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.pulsarnav.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Trip(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val durationSec: Long,
    val topSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val routeTitle: String
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(startTimeMs))

    val formattedDuration: String
        get() {
            val mins = durationSec / 60
            val secs = durationSec % 60
            return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        }
}

data class LifetimeStats(
    val totalTrips: Int,
    val totalDistanceKm: Double,
    val topSpeedEverKmh: Double
)

class RideTracker private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_trips", Context.MODE_PRIVATE)
    private val persistenceDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(persistenceDispatcher)

    companion object {
        private const val KEY_TRIP_LIST = "saved_trip_list"

        @Volatile
        private var instance: RideTracker? = null

        fun getInstance(context: Context): RideTracker {
            return instance ?: synchronized(this) {
                instance ?: RideTracker(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _tripHistory = MutableStateFlow<List<Trip>>(loadTrips())
    val tripHistory: StateFlow<List<Trip>> = _tripHistory.asStateFlow()

    private val _lifetimeStats = MutableStateFlow(calculateLifetimeStats(_tripHistory.value))
    val lifetimeStats: StateFlow<LifetimeStats> = _lifetimeStats.asStateFlow()

    // Live ride state
    private var isTracking = false
    private var rideStartTimeMs: Long = 0
    private var rideDistanceMeters: Double = 0.0
    private var topSpeedMps: Float = 0f
    private var lastLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!isTracking) return
            val last = lastLocation
            if (last != null) {
                val stepDist = last.distanceTo(location)
                if (stepDist > 1.0) { // filter GPS jitter
                    rideDistanceMeters += stepDist
                }
            }
            if (location.hasSpeed() && location.speed > topSpeedMps) {
                topSpeedMps = location.speed
            }
            lastLocation = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun onBikeConnected() {
        if (!AppSettings.getInstance(context).isRideTrackingEnabled) return
        if (isTracking) return

        isTracking = true
        rideStartTimeMs = System.currentTimeMillis()
        rideDistanceMeters = 0.0
        topSpeedMps = 0f
        lastLocation = null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener)
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener)
            }
        } catch (_: Exception) {}
    }

    fun onBikeDisconnected(): Trip? {
        if (!isTracking) return null
        isTracking = false

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        try {
            lm?.removeUpdates(locationListener)
        } catch (_: Exception) {}

        val endTime = System.currentTimeMillis()
        val durationSec = ((endTime - rideStartTimeMs) / 1000).coerceAtLeast(1)
        val distanceKm = rideDistanceMeters / 1000.0
        val topSpeedKmh = (topSpeedMps * 3.6).coerceAtLeast(0.0)
        val avgSpeedKmh = if (durationSec > 0) (distanceKm / (durationSec / 3600.0)) else 0.0

        if (distanceKm < 0.05) return null // Ignore negligible ignition flickers

        val trip = Trip(
            id = UUID.randomUUID().toString(),
            startTimeMs = rideStartTimeMs,
            endTimeMs = endTime,
            distanceKm = distanceKm,
            durationSec = durationSec,
            topSpeedKmh = topSpeedKmh,
            avgSpeedKmh = avgSpeedKmh,
            routeTitle = "Pulsar Ride"
        )

        saveTrip(trip)
        return trip
    }

    fun simulateTrip(distanceKm: Double, durationSec: Long, topSpeedKmh: Double, avgSpeedKmh: Double, title: String): Trip {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (durationSec * 1000)
        val trip = Trip(
            id = UUID.randomUUID().toString(),
            startTimeMs = startTime,
            endTimeMs = endTime,
            distanceKm = distanceKm,
            durationSec = durationSec,
            topSpeedKmh = topSpeedKmh,
            avgSpeedKmh = avgSpeedKmh,
            routeTitle = title
        )
        saveTrip(trip)
        return trip
    }

    /**
     * Manually records a ride (e.g. quick local drop-off without phone navigation connected).
     */
    fun addManualTrip(
        distanceKm: Double,
        durationSec: Long,
        topSpeedKmh: Double,
        avgSpeedKmh: Double,
        routeTitle: String,
        startTimeMs: Long = System.currentTimeMillis() - (durationSec * 1000)
    ): Trip {
        val endTime = startTimeMs + (durationSec * 1000)
        val computedAvg = if (avgSpeedKmh > 0) avgSpeedKmh else if (durationSec > 0) (distanceKm / (durationSec / 3600.0)) else 0.0
        val trip = Trip(
            id = UUID.randomUUID().toString(),
            startTimeMs = startTimeMs,
            endTimeMs = endTime,
            distanceKm = distanceKm,
            durationSec = durationSec,
            topSpeedKmh = topSpeedKmh,
            avgSpeedKmh = computedAvg,
            routeTitle = routeTitle.ifBlank { "Manual Logged Ride" }
        )
        saveTrip(trip)
        return trip
    }

    /**
     * Updates an existing trip's details (distance, duration, route name, speed).
     * Automatically recalculates average speed and lifetime stats.
     */
    fun updateTrip(
        id: String,
        distanceKm: Double,
        durationSec: Long,
        routeTitle: String,
        topSpeedKmh: Double? = null,
        avgSpeedKmh: Double? = null
    ): Boolean {
        val current = _tripHistory.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return false

        val oldTrip = current[index]
        val topSpeed = topSpeedKmh ?: oldTrip.topSpeedKmh
        val avgSpeed = avgSpeedKmh ?: if (durationSec > 0) {
            (distanceKm / (durationSec / 3600.0)).coerceAtLeast(0.0)
        } else {
            oldTrip.avgSpeedKmh
        }

        val updated = oldTrip.copy(
            distanceKm = distanceKm,
            durationSec = durationSec,
            routeTitle = routeTitle,
            topSpeedKmh = topSpeed,
            avgSpeedKmh = avgSpeed
        )

        current[index] = updated
        _tripHistory.value = current
        _lifetimeStats.value = calculateLifetimeStats(current)
        saveTrips(current)
        return true
    }

    /**
     * Deletes a recorded or simulated trip and updates lifetime stats.
     */
    fun deleteTrip(id: String): Boolean {
        val current = _tripHistory.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _tripHistory.value = current
            _lifetimeStats.value = calculateLifetimeStats(current)
            saveTrips(current)
        }
        return removed
    }

    /**
     * Imports an external list of trips (e.g. from Data Vault),
     * preventing duplicates by ID and updating lifetime statistics.
     */
    fun importTrips(importedTrips: List<Trip>): Int {
        val current = _tripHistory.value.toMutableList()
        val existingIds = current.map { it.id }.toSet()
        var addedCount = 0
        for (t in importedTrips) {
            if (t.id !in existingIds) {
                current.add(t)
                addedCount++
            }
        }
        if (addedCount > 0) {
            val sorted = current.sortedByDescending { it.startTimeMs }
            _tripHistory.value = sorted
            _lifetimeStats.value = calculateLifetimeStats(sorted)
            saveTrips(sorted)
        }
        return addedCount
    }

    private fun saveTrip(trip: Trip) {
        val current = _tripHistory.value.toMutableList()
        current.add(0, trip)
        _tripHistory.value = current
        _lifetimeStats.value = calculateLifetimeStats(current)
        saveTrips(current)
    }

    private fun saveTrips(trips: List<Trip>) {
        val snapshot = trips.toList()
        scope.launch {
            val jsonArray = JSONArray()
            for (t in snapshot) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("startTimeMs", t.startTimeMs)
                    put("endTimeMs", t.endTimeMs)
                    put("distanceKm", t.distanceKm)
                    put("durationSec", t.durationSec)
                    put("topSpeedKmh", t.topSpeedKmh)
                    put("avgSpeedKmh", t.avgSpeedKmh)
                    put("routeTitle", t.routeTitle)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_TRIP_LIST, jsonArray.toString()).apply()
        }
    }

    private fun loadTrips(): List<Trip> {
        val raw = prefs.getString(KEY_TRIP_LIST, null) ?: return emptyList()
        val list = mutableListOf<Trip>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Trip(
                        id = obj.getString("id"),
                        startTimeMs = obj.getLong("startTimeMs"),
                        endTimeMs = obj.getLong("endTimeMs"),
                        distanceKm = obj.getDouble("distanceKm"),
                        durationSec = obj.getLong("durationSec"),
                        topSpeedKmh = obj.getDouble("topSpeedKmh"),
                        avgSpeedKmh = obj.getDouble("avgSpeedKmh"),
                        routeTitle = obj.optString("routeTitle", "Pulsar Ride")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun calculateLifetimeStats(trips: List<Trip>): LifetimeStats {
        var totalDist = 0.0
        var topSpeed = 0.0
        for (t in trips) {
            totalDist += t.distanceKm
            if (t.topSpeedKmh > topSpeed) topSpeed = t.topSpeedKmh
        }
        return LifetimeStats(trips.size, totalDist, topSpeed)
    }
}

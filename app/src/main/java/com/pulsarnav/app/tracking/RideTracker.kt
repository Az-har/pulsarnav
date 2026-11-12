package com.pulsarnav.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
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
}

data class LiveRideSnapshot(
    val isTracking: Boolean,
    val currentSpeedKmh: Float,
    val distanceKm: Double,
    val lifetimeTrips: Int
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

    // Live ride state (guarded by stateLock across Binder/Main/BLE threads)
    private val stateLock = Any()
    private var isTracking = false
    private var rideStartTimeMs: Long = 0
    private var rideDistanceMeters: Double = 0.0
    private var topSpeedMps: Float = 0f
    private var lastLocation: Location? = null

    fun getLiveRideSnapshot(): LiveRideSnapshot {
        return synchronized(stateLock) {
            val speedKmh = if (isTracking && lastLocation?.hasSpeed() == true) {
                ((lastLocation?.speed ?: 0f) * 3.6f).coerceAtLeast(0f)
            } else {
                0f
            }
            LiveRideSnapshot(
                isTracking = isTracking,
                currentSpeedKmh = speedKmh,
                distanceKm = rideDistanceMeters / 1000.0,
                lifetimeTrips = _tripHistory.value.size
            )
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            synchronized(stateLock) {
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
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun onBikeConnected() {
        if (!AppSettings.getInstance(context).isRideTrackingEnabled) return
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        synchronized(stateLock) {
            if (isTracking) return

            isTracking = true
            rideStartTimeMs = System.currentTimeMillis()
            rideDistanceMeters = 0.0
            topSpeedMps = 0f
            lastLocation = null
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            synchronized(stateLock) { isTracking = false }
            return
        }
        try {
            if (hasFine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener)
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener)
            } else {
                synchronized(stateLock) { isTracking = false }
            }
        } catch (_: Exception) {
            synchronized(stateLock) { isTracking = false }
        }
    }

    fun onBikeDisconnected(): Trip? {
        val distanceKm: Double
        val startTime: Long
        val endTime = System.currentTimeMillis()
        val topSpeedKmh: Double
        synchronized(stateLock) {
            if (!isTracking) return null
            isTracking = false
            startTime = rideStartTimeMs
            distanceKm = rideDistanceMeters / 1000.0
            topSpeedKmh = (topSpeedMps * 3.6).coerceAtLeast(0.0)
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        try {
            lm?.removeUpdates(locationListener)
        } catch (_: Exception) {}

        val durationSec = ((endTime - startTime) / 1000).coerceAtLeast(1)
        val avgSpeedKmh = if (durationSec > 0) (distanceKm / (durationSec / 3600.0)) else 0.0

        if (distanceKm < 0.05) return null // Ignore negligible ignition flickers

        val trip = Trip(
            id = UUID.randomUUID().toString(),
            startTimeMs = startTime,
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
            saveTrips(sorted)
        }
        return addedCount
    }

    private fun saveTrip(trip: Trip) {
        val current = _tripHistory.value.toMutableList()
        current.add(0, trip)
        _tripHistory.value = current
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
}

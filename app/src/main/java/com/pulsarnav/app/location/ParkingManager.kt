package com.pulsarnav.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.pulsarnav.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

internal object ParkingFixValidator {
    const val MAX_FIX_AGE_MS = 5 * 60 * 1000L
    const val MAX_ACCEPTABLE_ACCURACY_METERS = 150f

    fun isAcceptableFix(
        fixTimeMs: Long,
        fixElapsedNanos: Long?,
        accuracyMeters: Float?,
        nowMs: Long = System.currentTimeMillis(),
        nowElapsedNanos: Long? = null
    ): Boolean {
        if (accuracyMeters != null && accuracyMeters > MAX_ACCEPTABLE_ACCURACY_METERS) {
            return false
        }
        if (fixElapsedNanos != null && nowElapsedNanos != null && fixElapsedNanos > 0L && nowElapsedNanos >= fixElapsedNanos) {
            val ageMs = (nowElapsedNanos - fixElapsedNanos) / 1_000_000L
            return ageMs in 0..MAX_FIX_AGE_MS
        }
        val wallAgeMs = nowMs - fixTimeMs
        return fixTimeMs > 0L && wallAgeMs in 0..MAX_FIX_AGE_MS
    }
}

data class ParkedLocation(
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val timestampMs: Long
)

class ParkingManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pulsarnav_parking", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val KEY_LAT = "park_lat"
        private const val KEY_LNG = "park_lng"
        private const val KEY_ADDRESS = "park_address"
        private const val KEY_TIMESTAMP = "park_timestamp"

        @Volatile
        private var instance: ParkingManager? = null

        fun getInstance(context: Context): ParkingManager {
            return instance ?: synchronized(this) {
                instance ?: ParkingManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _lastParkedLocation = MutableStateFlow<ParkedLocation?>(loadSavedLocation())
    val lastParkedLocation: StateFlow<ParkedLocation?> = _lastParkedLocation.asStateFlow()

    private fun loadSavedLocation(): ParkedLocation? {
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString(KEY_LNG, null)?.toDoubleOrNull() ?: return null
        val address = prefs.getString(KEY_ADDRESS, "Saved Parking Spot") ?: "Saved Parking Spot"
        val time = prefs.getLong(KEY_TIMESTAMP, 0L)
        return ParkedLocation(lat, lng, address, time)
    }

    @SuppressLint("MissingPermission")
    fun onBikeDisconnected() {
        if (!AppSettings.getInstance(context).isAutoParkEnabled) return

        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val candidates = mutableListOf<Location>()

        try {
            if (hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { candidates.add(it) }
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { candidates.add(it) }
            }
        } catch (_: Exception) { }

        val nowMs = System.currentTimeMillis()
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val validCandidates = candidates.filter { loc ->
            ParkingFixValidator.isAcceptableFix(
                fixTimeMs = loc.time,
                fixElapsedNanos = loc.elapsedRealtimeNanos,
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
                nowMs = nowMs,
                nowElapsedNanos = nowElapsedNanos
            )
        }

        val bestLocation = validCandidates.maxWithOrNull(
            compareBy<Location> { it.elapsedRealtimeNanos }
                .thenBy { if (it.hasAccuracy()) -it.accuracy else Float.NEGATIVE_INFINITY }
        )

        if (bestLocation != null) {
            saveLocation(bestLocation.latitude, bestLocation.longitude)
        }
    }

    fun saveLocation(lat: Double, lng: Double, customAddress: String? = null) {
        val timestamp = System.currentTimeMillis()
        scope.launch {
            val resolvedAddress = customAddress ?: resolveAddress(lat, lng)
            prefs.edit()
                .putString(KEY_LAT, lat.toString())
                .putString(KEY_LNG, lng.toString())
                .putString(KEY_ADDRESS, resolvedAddress)
                .putLong(KEY_TIMESTAMP, timestamp)
                .apply()

            _lastParkedLocation.value = ParkedLocation(lat, lng, resolvedAddress, timestamp)
        }
    }

    fun getWalkingDirectionsIntent(): Intent? {
        val loc = _lastParkedLocation.value ?: return null
        val uri = Uri.parse("google.navigation:q=${loc.latitude},${loc.longitude}&mode=w")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
    }

    fun getFallbackWalkingDirectionsIntent(): Intent? {
        val loc = _lastParkedLocation.value ?: return null
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${loc.latitude},${loc.longitude}&travelmode=walking")
        return Intent(Intent.ACTION_VIEW, uri)
    }

    private fun resolveAddress(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lng, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                val feature = addr.subLocality ?: addr.featureName ?: addr.thoroughfare
                val locality = addr.locality ?: addr.subAdminArea
                if (feature != null && locality != null) "$feature, $locality"
                else addr.getAddressLine(0) ?: "%.5f, %.5f".format(lat, lng)
            } else {
                "%.5f, %.5f".format(lat, lng)
            }
        } catch (_: Exception) {
            "%.5f, %.5f".format(lat, lng)
        }
    }
}

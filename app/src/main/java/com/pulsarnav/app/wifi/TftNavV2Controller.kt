package com.pulsarnav.app.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.debug.DebugLogRepository
import com.pulsarnav.app.navigation.NavigationIntentLauncher
import com.pulsarnav.app.tracking.RideTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Handles Bajaj TFT TCP 5558 NAV_V2 interactive menu requests (0x07..0x0F):
 * nearby POIs (Overpass API routed via cellular when on cluster SoftAP),
 * favorites (Home / Work / Favorite), recent trips, route previews, preferences,
 * and turning TBT navigation intents back on via NavigationIntentLauncher.
 */
class TftNavV2Controller(private val context: Context) {

    private val sequentialExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TftNavV2Thread").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + sequentialExecutor.asCoroutineDispatcher())

    @Volatile
    private var lastPoiList: List<NavV2IndexedPlace> = emptyList()

    @Volatile
    private var lastFavouritesList: List<NavV2IndexedPlace> = emptyList()

    @Volatile
    private var lastRecentTripsList: List<NavV2IndexedPlace> = emptyList()

    fun handleCommand(
        cmd: Int,
        payload: ByteArray,
        sendFrame: (ByteArray) -> Unit
    ) {
        val payloadCopy = payload.clone()
        val cmdName = DebugLogRepository.tcpCmdLabel(TftWifiProtocol.GROUP_DATA, cmd)
        DebugLogRepository.navV2(
            "NAV_V2_RX",
            "$cmdName (${payloadCopy.size}B)",
            DebugLogRepository.toCompactHex(payloadCopy, maxBytes = 12)
        )
        scope.launch {
            try {
                val response = processCommandSynchronously(cmd, payloadCopy)
                if (response != null) {
                    DebugLogRepository.navV2(
                        "NAV_V2_TX",
                        "$cmdName -> ${response.size}B frame",
                        DebugLogRepository.toCompactHex(response, maxBytes = 16)
                    )
                    sendFrame(response)
                }
            } catch (e: Exception) {
                Log.w(TAG, "NAV_V2 command 0x${cmd.toString(16)} failed: ${e.message}")
                DebugLogRepository.error("NAV_V2", "$cmdName failed: ${e.message}")
            }
        }
    }

    private fun processCommandSynchronously(cmd: Int, payload: ByteArray): ByteArray? {
        val settings = AppSettings.getInstance(context)
        val units = NavV2Units.from(settings.navV2Units)
        val routeMode = NavV2RouteMode.from(settings.navV2RouteMode)

        return when (cmd) {
            TftWifiProtocol.CMD_NAV_V2_GET_POI_LIST -> {
                val categoryIdx = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
                val fix = getBestLastKnownLocation()
                val rawPois = if (fix != null) {
                    fetchOverpassPois(categoryIdx, fix.latitude, fix.longitude, units, routeMode)
                } else {
                    emptyList()
                }
                val resolved = rawPois.ifEmpty {
                    buildFallbackPois(
                        categoryIndex = categoryIdx,
                        units = units,
                        routeMode = routeMode
                    )
                }
                lastPoiList = resolved
                TftNavV2Codec.buildResponse(cmd, TftNavV2Codec.encodeIndexedNameList(resolved))
            }

            TftWifiProtocol.CMD_NAV_V2_GET_POI_DETAIL -> {
                val idx = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
                val place = lastPoiList.getOrNull(idx)
                val fix = getBestLastKnownLocation()
                val detail = computePlaceDetail(
                    place = place,
                    originLat = fix?.latitude,
                    originLng = fix?.longitude,
                    units = units,
                    routeMode = routeMode,
                    fallbackLabel = "POI #${idx + 1}"
                )
                TftNavV2Codec.buildResponse(
                    cmd,
                    TftNavV2Codec.encodeDetail(idx, detail.first, detail.second, detail.third)
                )
            }

            TftWifiProtocol.CMD_NAV_V2_GET_FAVOURITES -> {
                val fix = getBestLastKnownLocation()
                val favs = buildConfiguredFavourites(
                    settings = settings,
                    originLat = fix?.latitude,
                    originLng = fix?.longitude,
                    units = units,
                    routeMode = routeMode
                )
                lastFavouritesList = favs
                TftNavV2Codec.buildResponse(cmd, TftNavV2Codec.encodeIndexedNameList(favs))
            }

            TftWifiProtocol.CMD_NAV_V2_GET_RECENT_TRIPS -> {
                val fix = getBestLastKnownLocation()
                val jsonRecents = parseSavedRecentDestsJson(settings.navV2RecentDestsJson)
                val rideTitles = RideTracker.getInstance(context).tripHistory.value.map { it.routeTitle }
                val merged = mergeRecentDestinations(
                    savedJsonPlaces = jsonRecents,
                    rideHistoryTitles = rideTitles,
                    originLat = fix?.latitude,
                    originLng = fix?.longitude,
                    units = units,
                    routeMode = routeMode
                )
                lastRecentTripsList = merged
                TftNavV2Codec.buildResponse(cmd, TftNavV2Codec.encodeIndexedNameList(merged))
            }

            TftWifiProtocol.CMD_NAV_V2_GET_TRIP_DETAIL -> {
                val idx = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
                val place = lastRecentTripsList.getOrNull(idx)
                val fix = getBestLastKnownLocation()
                val detail = computePlaceDetail(
                    place = place,
                    originLat = fix?.latitude,
                    originLng = fix?.longitude,
                    units = units,
                    routeMode = routeMode,
                    fallbackLabel = "Recent #${idx + 1}"
                )
                TftNavV2Codec.buildResponse(
                    cmd,
                    TftNavV2Codec.encodeDetail(idx, detail.first, detail.second, detail.third)
                )
            }

            TftWifiProtocol.CMD_NAV_V2_GET_ROUTE_PREVIEW -> {
                val source = NavV2Source.from(payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0)
                val idx = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
                val place = resolvePlaceForSource(source, idx)
                val fix = getBestLastKnownLocation()
                val (dist, eta) = computeDistanceAndEtaPair(
                    place = place,
                    originLat = fix?.latitude,
                    originLng = fix?.longitude,
                    units = units,
                    routeMode = routeMode
                )
                TftNavV2Codec.buildResponse(
                    cmd,
                    TftNavV2Codec.encodeRoutePreview(source, idx, routeMode, dist, eta)
                )
            }

            TftWifiProtocol.CMD_NAV_V2_SET_ROUTE_PREF -> {
                val parsed = TftNavV2Codec.parseSetRoutePreference(payload)
                val ackStatus = if (parsed != null) {
                    settings.navV2AvoidBitmask = parsed.bitmask
                    settings.navV2RouteMode = parsed.routeMode.value
                    settings.navV2Units = parsed.units.value
                    NavV2AckStatus.SUCCESS
                } else {
                    NavV2AckStatus.FAILURE
                }
                TftNavV2Codec.buildResponse(cmd, TftNavV2Codec.encodePreferenceAck(ackStatus))
            }

            TftWifiProtocol.CMD_NAV_V2_GET_ROUTE_PREF -> {
                val pref = NavV2RoutePreference(
                    bitmask = settings.navV2AvoidBitmask,
                    routeMode = routeMode,
                    units = units
                )
                TftNavV2Codec.buildResponse(cmd, TftNavV2Codec.encodePreference(pref))
            }

            TftWifiProtocol.CMD_NAV_V2_START_NAV -> {
                val source = NavV2Source.from(payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0)
                val idx = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
                val place = resolvePlaceForSource(source, idx)
                if (place == null) {
                    TftNavV2Codec.buildResponse(
                        cmd,
                        TftNavV2Codec.encodeNavStatus(NavV2NavStatus.FAILED, "Invalid destination")
                    )
                } else {
                    settings.navV2RecentDestsJson = appendSavedRecentDestJson(settings.navV2RecentDestsJson, place)
                    val targetQuery = place.address.ifBlank { place.name }
                    val launched = NavigationIntentLauncher.launchNavigation(
                        context = context,
                        query = targetQuery,
                        lat = place.latitude,
                        lng = place.longitude
                    )
                    val status = if (launched) NavV2NavStatus.STARTED else NavV2NavStatus.FAILED
                    TftNavV2Codec.buildResponse(cmd, TftNavV2Codec.encodeNavStatus(status, place.name))
                }
            }

            else -> TftTcpCodec.buildNavV2AckResponse(cmd)
        }
    }

    private fun resolvePlaceForSource(source: NavV2Source, index: Int): NavV2IndexedPlace? {
        return when (source) {
            NavV2Source.POI -> lastPoiList.getOrNull(index)
            NavV2Source.FAVOURITE -> lastFavouritesList.getOrNull(index)
            NavV2Source.RECENT -> lastRecentTripsList.getOrNull(index)
        }
    }

    private fun buildConfiguredFavourites(
        settings: AppSettings,
        originLat: Double?,
        originLng: Double?,
        units: NavV2Units,
        routeMode: NavV2RouteMode
    ): List<NavV2IndexedPlace> {
        val out = ArrayList<NavV2IndexedPlace>(3)
        var slotIdx = 0

        fun addSpot(label: String, addrRaw: String, latRaw: Double, lngRaw: Double, saveCoords: (Double, Double) -> Unit) {
            val addr = addrRaw.trim()
            var lat = latRaw
            var lng = lngRaw
            if (addr.isBlank() && !NavigationIntentLauncher.hasValidCoordinates(lat, lng)) {
                return
            }
            if (shouldGeocodeFavouriteAddress(label, addr) && !NavigationIntentLauncher.hasValidCoordinates(lat, lng)) {
                val geocoded = tryGeocodeAddress(addr)
                if (geocoded != null) {
                    lat = geocoded.first
                    lng = geocoded.second
                    saveCoords(lat, lng)
                }
            }
            val displayName = if (addr.isNotBlank()) "$label: $addr" else label
            val (dist, eta) = if (originLat != null && originLng != null && NavigationIntentLauncher.hasValidCoordinates(lat, lng)) {
                estimateRouteDistanceAndEta(originLat, originLng, lat, lng, units, routeMode)
            } else {
                "--" to "--"
            }
            out.add(
                NavV2IndexedPlace(
                    index = slotIdx++,
                    name = displayName.take(48),
                    latitude = lat,
                    longitude = lng,
                    address = addr.ifBlank { label },
                    distanceText = dist,
                    etaText = eta
                )
            )
        }

        addSpot("Home", settings.homeAddress, settings.homeLat, settings.homeLng) { lat, lng ->
            settings.homeLat = lat
            settings.homeLng = lng
        }
        addSpot("Work", settings.workAddress, settings.workLat, settings.workLng) { lat, lng ->
            settings.workLat = lat
            settings.workLng = lng
        }
        addSpot("Favorite", settings.favPlaceAddress, settings.favPlaceLat, settings.favPlaceLng) { lat, lng ->
            settings.favPlaceLat = lat
            settings.favPlaceLng = lng
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun tryGeocodeAddress(addressText: String): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val list: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val latch = CountDownLatch(1)
                var resolved: List<Address>? = null
                geocoder.getFromLocationName(addressText, 1) { addresses ->
                    resolved = addresses
                    latch.countDown()
                }
                latch.await(1500L, TimeUnit.MILLISECONDS)
                resolved
            } else {
                geocoder.getFromLocationName(addressText, 1)
            }
            val first = list?.firstOrNull() ?: return null
            if (NavigationIntentLauncher.hasValidCoordinates(first.latitude, first.longitude)) {
                first.latitude to first.longitude
            } else null
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (hasFine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        val nowMs = System.currentTimeMillis()
        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                val ageMs = nowMs - loc.time
                val acc = if (loc.hasAccuracy()) loc.accuracy else null
                if (!isAcceptableLocationFix(ageMs, acc)) continue
                if (best == null || loc.time > best.time) {
                    best = loc
                }
            } catch (_: Exception) {
            }
        }
        return best
    }

    private fun hasUsableDefaultInternet(cm: ConnectivityManager?): Boolean {
        if (cm == null) return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun fetchOverpassPois(
        categoryIndex: Int,
        lat: Double,
        lng: Double,
        units: NavV2Units,
        routeMode: NavV2RouteMode
    ): List<NavV2IndexedPlace> {
        val spec = categorySpec(categoryIndex)
        val overpassQuery = String.format(
            Locale.US,
            "[out:json][timeout:4];nwr(around:5000,%.5f,%.5f)%s;out center 10;",
            lat,
            lng,
            spec.overpassFilter
        )

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var cellularNetwork: Network? = null
        var callback: ConnectivityManager.NetworkCallback? = null

        if (cm != null && ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val latch = CountDownLatch(1)
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cellularNetwork = network
                        latch.countDown()
                    }

                    override fun onUnavailable() {
                        latch.countDown()
                    }
                }
                callback = cb
                cm.requestNetwork(req, cb, 2000)
                latch.await(2200L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.d(TAG, "Cellular internet request unavailable: ${e.message}")
            }
        }

        return try {
            if (cellularNetwork == null && !hasUsableDefaultInternet(cm)) {
                Log.d(TAG, "Skipping Overpass POI lookup: no cellular or validated default internet while on SoftAP")
                return emptyList()
            }
            val url = URL("https://overpass-api.de/api/interpreter")
            val conn = (cellularNetwork?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3500
            conn.readTimeout = 4500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("User-Agent", "PulsarNav-Android/1.0")
            val body = "data=" + URLEncoder.encode(overpassQuery, "UTF-8")
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }

            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return emptyList()
            }
            val rawJson = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            conn.disconnect()
            parseOverpassPoisJson(
                rawJson = rawJson,
                fallbackCategoryName = spec.label,
                originLat = lat,
                originLng = lng,
                units = units,
                routeMode = routeMode
            )
        } catch (e: Exception) {
            Log.d(TAG, "Overpass POI lookup skipped (${e.javaClass.simpleName}): ${e.message}")
            emptyList()
        } finally {
            if (cm != null && callback != null) {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "TftNavV2Controller"
        private const val MAX_NAV_LIST_ITEMS = 6
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val ROAD_WINDING_FACTOR = 1.28
        private const val MAX_LOCATION_AGE_MS = 30 * 60_000L
        private const val MAX_LOCATION_ACCURACY_M = 1000f

        internal fun shouldGeocodeFavouriteAddress(label: String, addrRaw: String): Boolean {
            val trimmed = addrRaw.trim()
            if (trimmed.length < 3) return false
            if (trimmed.equals("Home", ignoreCase = true)) return false
            if (trimmed.equals("Work", ignoreCase = true)) return false
            if (trimmed.equals(label.trim(), ignoreCase = true)) return false
            return true
        }

        internal fun isAcceptableLocationFix(ageMs: Long, accuracyMeters: Float?): Boolean {
            if (ageMs < -60_000L || ageMs > MAX_LOCATION_AGE_MS) return false
            if (accuracyMeters != null && (accuracyMeters <= 0f || accuracyMeters > MAX_LOCATION_ACCURACY_M)) {
                return false
            }
            return true
        }

        internal data class PoiCategorySpec(
            val index: Int,
            val label: String,
            val searchQuery: String,
            val overpassFilter: String
        )

        internal fun categorySpec(categoryIndex: Int): PoiCategorySpec {
            return when (categoryIndex) {
                0 -> PoiCategorySpec(0, "Petrol Pump", "petrol pump near me", """["amenity"="fuel"]""")
                1 -> PoiCategorySpec(1, "Parking", "parking near me", """["amenity"="parking"]""")
                2 -> PoiCategorySpec(2, "Food & Cafe", "restaurant near me", """["amenity"~"restaurant|cafe|fast_food"]""")
                3 -> PoiCategorySpec(3, "Hospital", "hospital near me", """["amenity"~"hospital|clinic|pharmacy"]""")
                4 -> PoiCategorySpec(4, "Bike Service", "motorcycle repair near me", """["shop"~"motorcycle|car_repair"]""")
                5 -> PoiCategorySpec(5, "ATM", "atm near me", """["amenity"~"atm|bank"]""")
                else -> PoiCategorySpec(categoryIndex, "Petrol Pump", "petrol pump near me", """["amenity"="fuel"]""")
            }
        }

        internal fun buildFallbackPois(
            categoryIndex: Int,
            units: NavV2Units,
            routeMode: NavV2RouteMode
        ): List<NavV2IndexedPlace> {
            val spec = categorySpec(categoryIndex)
            val dist = TftNavV2Codec.formatDistanceText(1500.0, units)
            val eta = estimateEtaForDistanceMeters(1500.0, routeMode)
            return listOf(
                NavV2IndexedPlace(
                    index = 0,
                    name = "${spec.label} (Search Nearby)",
                    // Leave coordinates as 0.0 so CMD_NAV_V2_START_NAV launches a category search
                    // ("petrol pump near me", etc.) instead of routing 0m to the rider's current fix.
                    latitude = 0.0,
                    longitude = 0.0,
                    address = spec.searchQuery,
                    distanceText = dist,
                    etaText = eta
                )
            )
        }

        internal fun parseOverpassPoisJson(
            rawJson: String,
            fallbackCategoryName: String,
            originLat: Double,
            originLng: Double,
            units: NavV2Units,
            routeMode: NavV2RouteMode,
            maxItems: Int = MAX_NAV_LIST_ITEMS
        ): List<NavV2IndexedPlace> {
            return try {
                val root = JSONObject(rawJson)
                val elements = root.optJSONArray("elements") ?: return emptyList()
                val candidates = ArrayList<Pair<Double, NavV2IndexedPlace>>()
                val seenNames = HashSet<String>()

                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val tags = el.optJSONObject("tags")
                    val center = el.optJSONObject("center")
                    val lat = el.optDouble("lat", Double.NaN).takeIf { !it.isNaN() }
                        ?: center?.optDouble("lat", Double.NaN)?.takeIf { !it.isNaN() }
                        ?: continue
                    val lng = el.optDouble("lon", Double.NaN).takeIf { !it.isNaN() }
                        ?: center?.optDouble("lon", Double.NaN)?.takeIf { !it.isNaN() }
                        ?: continue

                    val name = tags?.optString("name").orEmpty().trim()
                        .ifBlank { tags?.optString("brand").orEmpty().trim() }
                        .ifBlank { fallbackCategoryName }
                    val dedupKey = "${name.lowercase(Locale.ROOT)}:${(lat * 100).roundToInt()}:${(lng * 100).roundToInt()}"
                    if (!seenNames.add(dedupKey)) continue

                    val meters = haversineMeters(originLat, originLng, lat, lng) * ROAD_WINDING_FACTOR
                    val (distText, etaText) = formatDistanceAndEta(meters, units, routeMode)
                    candidates.add(
                        meters to NavV2IndexedPlace(
                            index = 0,
                            name = name.take(48),
                            latitude = lat,
                            longitude = lng,
                            address = "$name",
                            distanceText = distText,
                            etaText = etaText
                        )
                    )
                }

                candidates.sortBy { it.first }
                candidates.take(maxItems).mapIndexed { idx, (_, place) ->
                    place.copy(index = idx)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val rLat1 = Math.toRadians(lat1)
            val rLat2 = Math.toRadians(lat2)
            val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLng / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
            return EARTH_RADIUS_M * c
        }

        internal fun estimateEtaForDistanceMeters(meters: Double, routeMode: NavV2RouteMode): String {
            val avgSpeedKmh = when (routeMode) {
                NavV2RouteMode.FASTEST -> 42.0
                NavV2RouteMode.SHORTEST -> 34.0
                NavV2RouteMode.ECO -> 38.0
                NavV2RouteMode.AVOID_TOLLS -> 36.0
            }
            val hours = (meters.coerceAtLeast(0.0) / 1000.0) / avgSpeedKmh
            val mins = (hours * 60.0).roundToInt().coerceAtLeast(1)
            return TftNavV2Codec.formatEtaText(mins)
        }

        internal fun formatDistanceAndEta(
            roadMeters: Double,
            units: NavV2Units,
            routeMode: NavV2RouteMode
        ): Pair<String, String> {
            return TftNavV2Codec.formatDistanceText(roadMeters, units) to
                estimateEtaForDistanceMeters(roadMeters, routeMode)
        }

        internal fun estimateRouteDistanceAndEta(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            units: NavV2Units,
            routeMode: NavV2RouteMode
        ): Pair<String, String> {
            val roadMeters = haversineMeters(originLat, originLng, destLat, destLng) * ROAD_WINDING_FACTOR
            return formatDistanceAndEta(roadMeters, units, routeMode)
        }

        internal fun computeDistanceAndEtaPair(
            place: NavV2IndexedPlace?,
            originLat: Double?,
            originLng: Double?,
            units: NavV2Units,
            routeMode: NavV2RouteMode
        ): Pair<String, String> {
            if (place == null) return "--" to "--"
            if (originLat != null && originLng != null && place.hasCoordinates()) {
                return estimateRouteDistanceAndEta(originLat, originLng, place.latitude, place.longitude, units, routeMode)
            }
            val d = place.distanceText.ifBlank { "--" }
            val e = place.etaText.ifBlank { "--" }
            return d to e
        }

        internal fun computePlaceDetail(
            place: NavV2IndexedPlace?,
            originLat: Double?,
            originLng: Double?,
            units: NavV2Units,
            routeMode: NavV2RouteMode,
            fallbackLabel: String
        ): Triple<String, String, String> {
            val title = place?.name?.ifBlank { fallbackLabel } ?: fallbackLabel
            val (dist, eta) = computeDistanceAndEtaPair(place, originLat, originLng, units, routeMode)
            return Triple(title, dist, eta)
        }

        internal fun parseSavedRecentDestsJson(json: String): List<NavV2IndexedPlace> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = ArrayList<NavV2IndexedPlace>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    if (name.isBlank()) continue
                    list.add(
                        NavV2IndexedPlace(
                            index = i,
                            name = name,
                            latitude = obj.optDouble("lat", 0.0),
                            longitude = obj.optDouble("lng", 0.0),
                            address = obj.optString("address", name)
                        )
                    )
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun appendSavedRecentDestJson(existingJson: String, place: NavV2IndexedPlace, maxCount: Int = 5): String {
            val existing = parseSavedRecentDestsJson(existingJson)
            val deduped = ArrayList<NavV2IndexedPlace>(maxCount)
            deduped.add(place)
            val newKey = place.name.trim().lowercase(Locale.ROOT)
            for (item in existing) {
                if (item.name.trim().lowercase(Locale.ROOT) == newKey) continue
                deduped.add(item)
                if (deduped.size >= maxCount) break
            }
            val arr = JSONArray()
            for (item in deduped.take(maxCount)) {
                arr.put(
                    JSONObject().apply {
                        put("name", item.name)
                        put("lat", item.latitude)
                        put("lng", item.longitude)
                        put("address", item.address)
                    }
                )
            }
            return arr.toString()
        }

        internal fun mergeRecentDestinations(
            savedJsonPlaces: List<NavV2IndexedPlace>,
            rideHistoryTitles: List<String>,
            originLat: Double?,
            originLng: Double?,
            units: NavV2Units,
            routeMode: NavV2RouteMode,
            maxItems: Int = 5
        ): List<NavV2IndexedPlace> {
            val merged = ArrayList<NavV2IndexedPlace>(maxItems)
            val seen = HashSet<String>()

            fun appendPlace(raw: NavV2IndexedPlace) {
                val key = raw.name.trim().lowercase(Locale.ROOT)
                if (key.isEmpty() || !seen.add(key) || merged.size >= maxItems) return
                val (dist, eta) = computeDistanceAndEtaPair(raw, originLat, originLng, units, routeMode)
                merged.add(
                    raw.copy(
                        index = merged.size,
                        distanceText = dist,
                        etaText = eta
                    )
                )
            }

            savedJsonPlaces.forEach(::appendPlace)
            for (title in rideHistoryTitles) {
                val clean = title.trim()
                if (clean.isBlank() || clean.equals("Bajaj Ride", ignoreCase = true)) continue
                appendPlace(NavV2IndexedPlace(index = merged.size, name = clean.take(48), address = clean))
            }
            return merged
        }
    }
}

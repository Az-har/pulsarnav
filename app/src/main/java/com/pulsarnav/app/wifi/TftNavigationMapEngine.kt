package com.pulsarnav.app.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.debug.DebugCategory
import com.pulsarnav.app.debug.DebugLogRepository
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.navigation.NavigationIntentLauncher
import com.pulsarnav.app.parser.ParsedNavigationStep
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class GeoPoint(
    val lat: Double,
    val lng: Double
)

data class RouteStepPlan(
    val maneuver: Maneuver,
    val instructionTitle: String,
    val streetName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val startLocation: GeoPoint,
    val cumulativeMetersAtStepStart: Double,
    val cumulativeMetersAtStepEnd: Double,
    val roundaboutExit: Int = 0
)

data class ActiveRoutePlan(
    val destinationLabel: String,
    val destination: GeoPoint,
    val origin: GeoPoint,
    val polyline: List<GeoPoint>,
    val cumulativeMeters: DoubleArray,
    val steps: List<RouteStepPlan>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class ParsedMapTarget(
    val label: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val query: String? = null,
    val urlToResolve: String? = null
)

data class RouteProgressSnapshot(
    val nextStep: ParsedNavigationStep,
    val activeStepIndex: Int,
    val distanceToNextTurnMeters: Int,
    val remainingTotalMeters: Int,
    val remainingDurationSeconds: Int,
    val offRouteMeters: Double,
    val arrived: Boolean
)

data class TftLiveMapUiState(
    val hasGpsFix: Boolean = false,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val bearingDeg: Float = 0f,
    val speedKmh: Float = 0f,
    val zoomLevel: Int = 16,
    val headingUp: Boolean = true,
    val routeActive: Boolean = false,
    val destinationLabel: String = "",
    val remainingDistanceText: String = "",
    val remainingEtaText: String = "",
    val isLoadingRoute: Boolean = false,
    val statusMessage: String = ""
)

object TftMapMathAndParser {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val ARRIVAL_THRESHOLD_M = 35.0
    const val OFF_ROUTE_REROUTE_THRESHOLD_M = 65.0

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2.0).pow(2.0) + cos(rLat1) * cos(rLat2) * sin(dLng / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
        return EARTH_RADIUS_M * c
    }

    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lng2 - lng1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    fun latLngToWorldPixel(lat: Double, lng: Double, zoom: Int): Pair<Double, Double> {
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val scale = 256.0 * (1 shl zoom.coerceIn(1, 20))
        val x = ((lng + 180.0) / 360.0) * scale
        val rad = Math.toRadians(clampedLat)
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * scale
        return x to y
    }

    fun decodePolyline6(encoded: String, precision: Double = 1e6): List<GeoPoint> {
        if (encoded.isBlank()) return emptyList()
        val points = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0L
        var lng = 0L
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0L
            do {
                if (index >= len) return points
                b = encoded[index++].code - 63
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            lat += dLat

            shift = 0
            result = 0L
            do {
                if (index >= len) return points
                b = encoded[index++].code - 63
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            lng += dLng

            points.add(GeoPoint(lat / precision, lng / precision))
        }
        return points
    }

    fun parseSharedMapInput(rawText: String?): ParsedMapTarget? {
        val text = rawText?.trim() ?: return null
        if (text.isBlank()) return null

        val urlMatch = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)?.value
            ?.trimEnd(',', '.', ')', ']')
        if (urlMatch != null) {
            val lower = urlMatch.lowercase(Locale.ROOT)
            if (lower.contains("maps.app.goo.gl") || lower.contains("goo.gl/maps")) {
                val hintLabel = extractLabelAroundUrl(text, urlMatch) ?: "Shared Pin"
                return ParsedMapTarget(label = hintLabel, urlToResolve = urlMatch)
            }
            parseGoogleMapsUrl(urlMatch)?.let { parsed ->
                val customPrefix = extractLabelAroundUrl(text, urlMatch)
                return if (!customPrefix.isNullOrBlank() && parsed.label == "Destination") {
                    parsed.copy(label = customPrefix)
                } else {
                    parsed
                }
            }
        }

        if (text.startsWith("geo:", ignoreCase = true)) {
            val withoutScheme = text.substring(4)
            val qIdx = withoutScheme.indexOf('?')
            val coordsPart = if (qIdx >= 0) withoutScheme.substring(0, qIdx) else withoutScheme
            val queryPart = if (qIdx >= 0) withoutScheme.substring(qIdx + 1) else ""
            val qParam = extractQueryParam(queryPart, "q")
            parseLatLngPair(coordsPart)?.let { (la, ln) ->
                if (NavigationIntentLauncher.hasValidCoordinates(la, ln)) {
                    val label = qParam?.let { cleanLabelFromQuery(it) } ?: "Shared Location"
                    return ParsedMapTarget(label = label, lat = la, lng = ln)
                }
            }
            if (!qParam.isNullOrBlank()) {
                parseLatLngPair(qParam)?.let { (la, ln) ->
                    return ParsedMapTarget(label = "Shared Location", lat = la, lng = ln)
                }
                return ParsedMapTarget(label = qParam.take(48), query = qParam)
            }
        }

        if (text.startsWith("google.navigation:", ignoreCase = true)) {
            val q = extractQueryParam(text.substringAfter(':', ""), "q")
            if (!q.isNullOrBlank()) {
                parseLatLngPair(q)?.let { (la, ln) ->
                    return ParsedMapTarget(label = "Navigation Target", lat = la, lng = ln)
                }
                return ParsedMapTarget(label = q.take(48), query = q)
            }
        }

        parseLatLngPair(text)?.let { (la, ln) ->
            return ParsedMapTarget(label = String.format(Locale.US, "%.4f, %.4f", la, ln), lat = la, lng = ln)
        }

        return ParsedMapTarget(label = text.take(48), query = text)
    }

    fun parseGoogleMapsUrl(url: String): ParsedMapTarget? {
        val placeLabel = Regex("""/maps/place/([^/@?]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
            ?.let { safeUrlDecode(it.replace('+', ' ')).trim() }
            ?.takeIf { it.isNotBlank() }

        // Exact pin coordinates inside Google Maps URL data blocks: !3d<lat>!4d<lng>
        val bangPin = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""").find(url)
        if (bangPin != null) {
            val la = bangPin.groupValues[1].toDoubleOrNull()
            val ln = bangPin.groupValues[2].toDoubleOrNull()
            if (la != null && ln != null && NavigationIntentLauncher.hasValidCoordinates(la, ln)) {
                return ParsedMapTarget(label = placeLabel ?: "Destination", lat = la, lng = ln)
            }
        }

        val queryString = url.substringAfter('?', "")
        val qValue = extractQueryParam(queryString, "q")
            ?: extractQueryParam(queryString, "destination")
            ?: extractQueryParam(queryString, "query")
        if (!qValue.isNullOrBlank()) {
            parseLatLngPair(qValue)?.let { (la, ln) ->
                return ParsedMapTarget(label = placeLabel ?: "Destination", lat = la, lng = ln)
            }
        }

        // Viewport center @lat,lng
        val atCoords = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""").find(url)
        if (atCoords != null) {
            val la = atCoords.groupValues[1].toDoubleOrNull()
            val ln = atCoords.groupValues[2].toDoubleOrNull()
            if (la != null && ln != null && NavigationIntentLauncher.hasValidCoordinates(la, ln)) {
                return ParsedMapTarget(label = placeLabel ?: "Destination", lat = la, lng = ln)
            }
        }

        if (!qValue.isNullOrBlank()) {
            return ParsedMapTarget(label = (placeLabel ?: qValue).take(48), query = qValue)
        }
        if (!placeLabel.isNullOrBlank()) {
            return ParsedMapTarget(label = placeLabel.take(48), query = placeLabel)
        }
        return null
    }

    fun mapOsrmManeuver(type: String?, modifier: String?): Maneuver {
        val t = type?.lowercase(Locale.ROOT)?.trim().orEmpty()
        val m = modifier?.lowercase(Locale.ROOT)?.trim().orEmpty()
        return when (t) {
            "arrive" -> Maneuver.DESTINATION_REACHED
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> {
                if (m.contains("left")) Maneuver.ROUNDABOUT_LEFT else Maneuver.ROUNDABOUT_RIGHT
            }
            "fork" -> if (m.contains("left")) Maneuver.FORK_LEFT else Maneuver.FORK_RIGHT
            "off ramp", "on ramp" -> if (m.contains("left")) Maneuver.RAMP_LEFT else Maneuver.RAMP_RIGHT
            "merge" -> Maneuver.MERGE
            "depart" -> Maneuver.STRAIGHT
            "end of road", "turn", "new name", "continue", "notification" -> when (m) {
                "uturn" -> Maneuver.U_TURN_RIGHT
                "sharp left" -> Maneuver.TURN_SHARP_LEFT
                "slight left" -> Maneuver.TURN_SLIGHT_LEFT
                "left" -> Maneuver.TURN_LEFT
                "sharp right" -> Maneuver.TURN_SHARP_RIGHT
                "slight right" -> Maneuver.TURN_SLIGHT_RIGHT
                "right" -> Maneuver.TURN_RIGHT
                else -> Maneuver.STRAIGHT
            }
            else -> when {
                m.contains("uturn") -> Maneuver.U_TURN_RIGHT
                m.contains("sharp left") -> Maneuver.TURN_SHARP_LEFT
                m.contains("slight left") -> Maneuver.TURN_SLIGHT_LEFT
                m.contains("left") -> Maneuver.TURN_LEFT
                m.contains("sharp right") -> Maneuver.TURN_SHARP_RIGHT
                m.contains("slight right") -> Maneuver.TURN_SLIGHT_RIGHT
                m.contains("right") -> Maneuver.TURN_RIGHT
                else -> Maneuver.STRAIGHT
            }
        }
    }

    fun parseOsrmRouteResponse(
        rawJson: String,
        destinationLabel: String,
        origin: GeoPoint,
        destination: GeoPoint
    ): ActiveRoutePlan? {
        return try {
            val root = JSONObject(rawJson)
            if (root.optString("code") != "Ok") return null
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val route0 = routes.getJSONObject(0)
            val geometry = route0.optString("geometry")
            val polyline = decodePolyline6(geometry)
            if (polyline.size < 2) return null

            val cumulative = DoubleArray(polyline.size)
            for (i in 1 until polyline.size) {
                cumulative[i] = cumulative[i - 1] + haversineMeters(
                    polyline[i - 1].lat, polyline[i - 1].lng,
                    polyline[i].lat, polyline[i].lng
                )
            }

            val totalDist = route0.optDouble("distance", cumulative.last())
            val totalDur = route0.optDouble("duration", totalDist / 10.0)
            val parsedSteps = ArrayList<RouteStepPlan>()
            val legs = route0.optJSONArray("legs")
            var cursorDist = 0.0
            if (legs != null) {
                for (l in 0 until legs.length()) {
                    val stepsArr = legs.getJSONObject(l).optJSONArray("steps") ?: continue
                    for (s in 0 until stepsArr.length()) {
                        val st = stepsArr.getJSONObject(s)
                        val mObj = st.optJSONObject("maneuver") ?: JSONObject()
                        val mType = mObj.optString("type")
                        val mMod = mObj.optString("modifier")
                        val exitNum = mObj.optInt("exit", 0)
                        val locArr = mObj.optJSONArray("location")
                        val stLoc = if (locArr != null && locArr.length() >= 2) {
                            GeoPoint(locArr.getDouble(1), locArr.getDouble(0))
                        } else {
                            polyline.first()
                        }
                        val stepDist = st.optDouble("distance", 0.0).coerceAtLeast(0.0)
                        val stepDur = st.optDouble("duration", 0.0).coerceAtLeast(0.0)
                        val roadName = st.optString("name", "").trim()
                        val maneuver = mapOsrmManeuver(mType, mMod)
                        val title = roadName.ifBlank { maneuver.description }
                        parsedSteps.add(
                            RouteStepPlan(
                                maneuver = maneuver,
                                instructionTitle = title,
                                streetName = roadName.ifBlank { destinationLabel },
                                distanceMeters = stepDist,
                                durationSeconds = stepDur,
                                startLocation = stLoc,
                                cumulativeMetersAtStepStart = cursorDist,
                                cumulativeMetersAtStepEnd = cursorDist + stepDist,
                                roundaboutExit = exitNum
                            )
                        )
                        cursorDist += stepDist
                    }
                }
            }
            if (parsedSteps.isEmpty()) return null

            ActiveRoutePlan(
                destinationLabel = destinationLabel,
                destination = destination,
                origin = origin,
                polyline = polyline,
                cumulativeMeters = cumulative,
                steps = parsedSteps,
                totalDistanceMeters = totalDist,
                totalDurationSeconds = totalDur
            )
        } catch (_: Exception) {
            null
        }
    }

    fun computeRouteProgress(
        route: ActiveRoutePlan,
        lat: Double,
        lng: Double,
        speedMps: Float,
        nowMs: Long = System.currentTimeMillis()
    ): RouteProgressSnapshot {
        val poly = route.polyline
        var bestSegDistM = Double.MAX_VALUE
        var bestCumDistM = 0.0

        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        for (i in 0 until poly.lastIndex) {
            val a = poly[i]
            val b = poly[i + 1]
            val dxAB = (b.lng - a.lng) * cosLat
            val dyAB = b.lat - a.lat
            val lenSq = dxAB * dxAB + dyAB * dyAB
            val t = if (lenSq > 1e-12) {
                val dxAP = (lng - a.lng) * cosLat
                val dyAP = lat - a.lat
                ((dxAP * dxAB + dyAP * dyAB) / lenSq).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val projLat = a.lat + t * (b.lat - a.lat)
            val projLng = a.lng + t * (b.lng - a.lng)
            val d = haversineMeters(lat, lng, projLat, projLng)
            if (d < bestSegDistM) {
                bestSegDistM = d
                val segLen = route.cumulativeMeters[i + 1] - route.cumulativeMeters[i]
                bestCumDistM = route.cumulativeMeters[i] + t * segLen
            }
        }

        val totalPolyLen = route.cumulativeMeters.lastOrNull() ?: route.totalDistanceMeters
        val distToDestM = max(
            0.0,
            min(
                haversineMeters(lat, lng, route.destination.lat, route.destination.lng),
                totalPolyLen - bestCumDistM
            )
        )
        val arrived = distToDestM <= ARRIVAL_THRESHOLD_M ||
            haversineMeters(lat, lng, route.destination.lat, route.destination.lng) <= ARRIVAL_THRESHOLD_M

        val scaleFactor = if (totalPolyLen > 1.0) route.totalDistanceMeters / totalPolyLen else 1.0
        val scaledProgressM = bestCumDistM * scaleFactor

        var nextStepIdx = route.steps.lastIndex
        for (idx in route.steps.indices) {
            if (route.steps[idx].cumulativeMetersAtStepStart > scaledProgressM + 10.0) {
                nextStepIdx = idx
                break
            }
        }
        if (arrived) {
            nextStepIdx = route.steps.lastIndex
        }
        val currentStep = route.steps[(nextStepIdx - 1).coerceAtLeast(0)]
        val upcomingStep = route.steps[nextStepIdx]

        val distToNextTurn = if (arrived) {
            0
        } else {
            max(0, (upcomingStep.cumulativeMetersAtStepStart - scaledProgressM).roundToInt())
        }

        val remTotalM = if (arrived) 0 else max(0, (route.totalDistanceMeters - scaledProgressM).roundToInt())
        val avgSpeed = if (speedMps > 2.5f) speedMps.toDouble() else 9.7 // ~35 km/h urban motorcycle fallback
        val ratio = if (route.totalDistanceMeters > 1.0) remTotalM / route.totalDistanceMeters else 0.0
        val osrmRemainingSec = (route.totalDurationSeconds * ratio).roundToInt()
        val liveRemainingSec = if (speedMps > 4.0f) {
            ((osrmRemainingSec + (remTotalM / avgSpeed)) / 2.0).roundToInt()
        } else {
            osrmRemainingSec
        }

        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs + liveRemainingSec * 1000L
        }
        val hr12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val min = cal.get(Calendar.MINUTE)
        val isAm = cal.get(Calendar.AM_PM) == Calendar.AM

        val streetLabel = if (arrived) {
            route.destinationLabel
        } else {
            upcomingStep.streetName.ifBlank { currentStep.streetName }
        }
        val parsed = ParsedNavigationStep(
            maneuver = if (arrived) Maneuver.DESTINATION_REACHED else upcomingStep.maneuver,
            distanceMeters = distToNextTurn,
            totalDistanceMeters = remTotalM,
            streetName = streetLabel,
            rawDistanceText = formatDistanceMeters(distToNextTurn),
            etaHour = hr12,
            etaMinute = min,
            etaIsAm = isAm,
            roundaboutExit = upcomingStep.roundaboutExit
        )

        return RouteProgressSnapshot(
            nextStep = parsed,
            activeStepIndex = nextStepIdx,
            distanceToNextTurnMeters = distToNextTurn,
            remainingTotalMeters = remTotalM,
            remainingDurationSeconds = liveRemainingSec,
            offRouteMeters = bestSegDistM,
            arrived = arrived
        )
    }

    fun formatDistanceMeters(meters: Int): String {
        return when {
            meters >= 10_000 -> String.format(Locale.US, "%.0f km", meters / 1000f)
            meters >= 1000 -> String.format(Locale.US, "%.1f km", meters / 1000f)
            else -> "$meters m"
        }
    }

    fun formatEtaDuration(seconds: Int): String {
        val mins = (seconds / 60).coerceAtLeast(1)
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "${h}h ${m}m" else "$mins min"
    }

    private fun extractLabelAroundUrl(fullText: String, url: String): String? {
        val before = fullText.substringBefore(url).trim()
            .removePrefix("Dropped pin")
            .trim(' ', '-', ':', '\n', '\r')
        if (before.length in 2..48) return before
        return null
    }

    private fun extractQueryParam(queryString: String, key: String): String? {
        if (queryString.isBlank()) return null
        for (pair in queryString.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq)
            if (k.equals(key, ignoreCase = true)) {
                return safeUrlDecode(pair.substring(eq + 1))
            }
        }
        return null
    }

    private fun parseLatLngPair(raw: String): Pair<Double, Double>? {
        val m = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)(?:\s*\(.*\))?\s*$""").find(raw) ?: return null
        val la = m.groupValues[1].toDoubleOrNull() ?: return null
        val ln = m.groupValues[2].toDoubleOrNull() ?: return null
        if (!NavigationIntentLauncher.hasValidCoordinates(la, ln)) return null
        return la to ln
    }

    private fun cleanLabelFromQuery(raw: String): String? {
        val paren = Regex("""\(([^)]+)\)""").find(raw)?.groupValues?.getOrNull(1)?.trim()
        if (!paren.isNullOrBlank()) return paren.take(48)
        if (parseLatLngPair(raw) != null) return null
        return raw.trim().takeIf { it.isNotBlank() }?.take(48)
    }

    private fun safeUrlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}

class OsmTileCache(private val context: Context) {

    private val ramCache = object : LruCache<String, Bitmap>(48) {}
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val fetchExecutor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "OsmTileFetchThread").apply { isDaemon = true }
    }
    private val diskDir = File(context.cacheDir, "osm_tiles_v2").apply { mkdirs() }
    private val lastPruneMs = java.util.concurrent.atomic.AtomicLong(0L)

    fun getOrRequestTile(
        zoom: Int,
        tileX: Int,
        tileY: Int,
        onTileLoaded: () -> Unit
    ): Bitmap? {
        val n = 1 shl zoom
        if (tileY < 0 || tileY >= n) return null
        val wrappedX = ((tileX % n) + n) % n
        val key = "osm_${zoom}_${wrappedX}_${tileY}"

        ramCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        if (inFlight.add(key)) {
            fetchExecutor.execute {
                try {
                    val file = File(diskDir, "$key.png")
                    val bytes: ByteArray? = if (file.exists() && file.length() > 100L) {
                        file.readBytes()
                    } else {
                        val fetched = CellularNetworkHttp.getBytes(
                            context = context,
                            url = "https://tile.openstreetmap.org/$zoom/$wrappedX/$tileY.png",
                            connectTimeoutMs = 3500,
                            readTimeoutMs = 4500,
                            maxBytes = 512 * 1024
                        ) ?: CellularNetworkHttp.getBytes(
                            context = context,
                            url = "https://a.tile.openstreetmap.fr/hot/$zoom/$wrappedX/$tileY.png",
                            connectTimeoutMs = 3500,
                            readTimeoutMs = 4500,
                            maxBytes = 512 * 1024
                        )
                        if (fetched != null && fetched.size > 100) {
                            try {
                                file.writeBytes(fetched)
                                pruneDiskCacheIfNeeded()
                            } catch (_: Exception) {
                            }
                        }
                        fetched
                    }

                    if (bytes != null) {
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (decoded != null) {
                            ramCache.put(key, decoded)
                            onTileLoaded()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return null
    }

    private fun pruneDiskCacheIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPruneMs.get() < 30_000L) return
        lastPruneMs.set(now)
        try {
            val files = diskDir.listFiles() ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= 14L * 1024L * 1024L) return
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (totalBytes <= 10L * 1024L * 1024L) break
                val len = f.length()
                if (f.delete()) totalBytes -= len
            }
        } catch (_: Exception) {
        }
    }
}

class TftNavigationMapEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TftNavMapEngine"
        private const val REROUTE_COOLDOWN_MS = 12_000L

        @Volatile
        private var instance: TftNavigationMapEngine? = null

        fun getInstance(context: Context): TftNavigationMapEngine {
            return instance ?: synchronized(this) {
                instance ?: TftNavigationMapEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appSettings = AppSettings.getInstance(context)
    private val bleManager = BajajBleManager.getInstance(context)
    private val tileCache = OsmTileCache(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(
        TftLiveMapUiState(
            zoomLevel = appSettings.tftMapZoom,
            headingUp = appSettings.tftMapHeadingUp
        )
    )
    val uiState: StateFlow<TftLiveMapUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeRoutePlan: ActiveRoutePlan? = null
    @Volatile
    private var lastRouteSnapshot: RouteProgressSnapshot? = null
    @Volatile
    private var lastRerouteAttemptMs = 0L
    @Volatile
    private var ownsBleTbtStream = false
    @Volatile
    private var gpsListening = false

    private var onMapInvalidated: (() -> Unit)? = null

    private val routePath = Path()
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val nightTileFilter = ColorMatrixColorFilter(
        ColorMatrix().apply {
            // Desaturate heavily before inverting so parks/water stay calm dark slate instead of purple
            val sat = ColorMatrix().apply { setSaturation(0.25f) }
            val inv = ColorMatrix(
                floatArrayOf(
                    -0.84f, 0f, 0f, 0f, 228f,
                    0f, -0.82f, 0f, 0f, 232f,
                    0f, 0f, -0.76f, 0f, 238f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            postConcat(sat)
            postConcat(inv)
        }
    )
    private val dayTileFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.90f, 0f, 0f, 0f, 12f,
                0f, 0.90f, 0f, 0f, 12f,
                0f, 0f, 0.90f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    private val bgPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val routeCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 12f
        color = Color.parseColor("#0B3B8C")
    }
    private val routeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 7f
        color = Color.parseColor("#38BDF8")
    }
    private val markerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4038BDF8")
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38BDF8")
    }
    private val markerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val destPinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 5f
    }
    private val rectTmp = RectF()
    private val arrowPath = Path()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onNewLocation(location)
        }

        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun setInvalidationListener(listener: (() -> Unit)?) {
        onMapInvalidated = listener
    }

    @SuppressLint("MissingPermission")
    fun startLocationTracking() {
        seedFromLastKnownLocation()
        if (gpsListening) return
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            _uiState.value = _uiState.value.copy(statusMessage = "Grant Location permission for Live Map")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            if (hasFine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, locationListener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500L, 10f, locationListener, Looper.getMainLooper())
            }
            gpsListening = true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start GPS updates: ${e.message}")
        }
    }

    fun stopLocationTracking() {
        if (!gpsListening) return
        gpsListening = false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedFromLastKnownLocation() {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.time > best.time) best = loc
            } catch (_: Exception) {
            }
        }
        if (best != null) {
            onNewLocation(best)
        }
    }

    private fun onNewLocation(loc: Location) {
        val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).coerceIn(0f, 199f) else 0f
        val prev = _uiState.value
        val bearing = when {
            loc.hasBearing() && loc.bearing != 0f -> loc.bearing
            prev.hasGpsFix && TftMapMathAndParser.haversineMeters(prev.lat, prev.lng, loc.latitude, loc.longitude) > 4.0 -> {
                TftMapMathAndParser.bearingDegrees(prev.lat, prev.lng, loc.latitude, loc.longitude)
            }
            else -> prev.bearingDeg
        }
        _uiState.value = prev.copy(
            hasGpsFix = true,
            lat = loc.latitude,
            lng = loc.longitude,
            bearingDeg = bearing,
            speedKmh = speedKmh
        )

        val route = activeRoutePlan
        if (route != null) {
            val snap = TftMapMathAndParser.computeRouteProgress(
                route = route,
                lat = loc.latitude,
                lng = loc.longitude,
                speedMps = if (loc.hasSpeed()) loc.speed else 0f
            )
            lastRouteSnapshot = snap
            _uiState.value = _uiState.value.copy(
                routeActive = !snap.arrived,
                remainingDistanceText = TftMapMathAndParser.formatDistanceMeters(snap.remainingTotalMeters),
                remainingEtaText = TftMapMathAndParser.formatEtaDuration(snap.remainingDurationSeconds),
                statusMessage = if (snap.arrived) "Arrived at ${route.destinationLabel}" else ""
            )
            pushInternalTbtIfPreferred(snap.nextStep)
            if (snap.arrived) {
                activeRoutePlan = null
            } else if (snap.offRouteMeters > TftMapMathAndParser.OFF_ROUTE_REROUTE_THRESHOLD_M) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRerouteAttemptMs > REROUTE_COOLDOWN_MS) {
                    lastRerouteAttemptMs = now
                    scope.launch {
                        computeOsrmRoute(GeoPoint(loc.latitude, loc.longitude), route.destination, route.destinationLabel)
                    }
                }
            }
        }
        onMapInvalidated?.invoke()
    }

    private fun pushInternalTbtIfPreferred(step: ParsedNavigationStep) {
        if (GoogleMapsNotificationListener.hasActiveExternalNavigation() || bleManager.isSimulationRunning.value) {
            return
        }
        ownsBleTbtStream = true
        bleManager.updateNavigationStep(step)
    }

    fun getCurrentStepForHud(): ParsedNavigationStep? {
        return GoogleMapsNotificationListener.currentNavStep.value ?: lastRouteSnapshot?.nextStep
    }

    fun adjustZoom(delta: Int): Int {
        val next = (_uiState.value.zoomLevel + delta).coerceIn(12, 18)
        appSettings.tftMapZoom = next
        _uiState.value = _uiState.value.copy(zoomLevel = next)
        onMapInvalidated?.invoke()
        return next
    }

    fun toggleHeadingUp(): Boolean {
        val next = !_uiState.value.headingUp
        appSettings.tftMapHeadingUp = next
        _uiState.value = _uiState.value.copy(headingUp = next)
        onMapInvalidated?.invoke()
        return next
    }

    fun clearActiveRoute() {
        activeRoutePlan = null
        lastRouteSnapshot = null
        _uiState.value = _uiState.value.copy(
            routeActive = false,
            destinationLabel = "",
            remainingDistanceText = "",
            remainingEtaText = "",
            isLoadingRoute = false,
            statusMessage = ""
        )
        if (ownsBleTbtStream && !GoogleMapsNotificationListener.hasActiveExternalNavigation()) {
            ownsBleTbtStream = false
            bleManager.stopNavigation()
        }
        onMapInvalidated?.invoke()
    }

    /**
     * Resolves a shared Google Maps URL, `geo:` URI, coordinate pair, or address query over
     * Cellular data and computes an OSRM route + offline-safe Live Map + BLE Turn-by-Turn stream.
     */
    fun startRouteFromInput(rawInput: String, onResult: ((Boolean, String) -> Unit)? = null) {
        val initial = TftMapMathAndParser.parseSharedMapInput(rawInput)
        if (initial == null) {
            onResult?.invoke(false, "Could not parse destination")
            return
        }
        startLocationTracking()
        _uiState.value = _uiState.value.copy(
            isLoadingRoute = true,
            destinationLabel = initial.label,
            statusMessage = "Resolving destination..."
        )
        onMapInvalidated?.invoke()

        scope.launch {
            try {
                var target = initial
                val shortUrl = target.urlToResolve
                if (!shortUrl.isNullOrBlank()) {
                    val resolvedUrl = CellularNetworkHttp.resolveFinalUrl(context, shortUrl)
                    val parsedResolved = TftMapMathAndParser.parseGoogleMapsUrl(resolvedUrl)
                    if (parsedResolved != null) {
                        target = if (target.label != "Shared Pin" && parsedResolved.label == "Destination") {
                            parsedResolved.copy(label = target.label)
                        } else {
                            parsedResolved
                        }
                    }
                }

                var destLat = target.lat
                var destLng = target.lng
                var destLabel = target.label
                val targetQuery = target.query
                if ((destLat == null || destLng == null) && !targetQuery.isNullOrBlank()) {
                    val geo = geocodeQuery(targetQuery)
                    if (geo != null) {
                        destLat = geo.first
                        destLng = geo.second
                        if (destLabel.isBlank()) destLabel = targetQuery.take(40)
                    }
                }

                if (destLat == null || destLng == null || !NavigationIntentLauncher.hasValidCoordinates(destLat, destLng)) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingRoute = false,
                        statusMessage = "Location not found — check mobile data"
                    )
                    onMapInvalidated?.invoke()
                    onResult?.invoke(false, "Could not find coordinates for \"$destLabel\"")
                    return@launch
                }

                val originLat = _uiState.value.lat
                val originLng = _uiState.value.lng
                if (!_uiState.value.hasGpsFix || !NavigationIntentLauncher.hasValidCoordinates(originLat, originLng)) {
                    // Center map preview on destination until GPS fix arrives
                    _uiState.value = _uiState.value.copy(
                        isLoadingRoute = false,
                        destinationLabel = destLabel,
                        lat = destLat,
                        lng = destLng,
                        statusMessage = "Waiting for GPS lock ($destLabel)"
                    )
                    onMapInvalidated?.invoke()
                    onResult?.invoke(false, "Waiting for GPS fix; centered on $destLabel")
                    return@launch
                }

                val route = computeOsrmRoute(GeoPoint(originLat, originLng), GeoPoint(destLat, destLng), destLabel)
                if (route != null) {
                    val snap = TftMapMathAndParser.computeRouteProgress(route, originLat, originLng, 0f)
                    lastRouteSnapshot = snap
                    pushInternalTbtIfPreferred(snap.nextStep)
                    DebugLogRepository.logRateLimited(
                        key = "osrm_route_ok",
                        minIntervalMs = 2000L,
                        category = DebugCategory.MAPS,
                        tag = "LiveMap",
                        summary = "Route ready -> $destLabel (${TftMapMathAndParser.formatDistanceMeters(snap.remainingTotalMeters)})"
                    )
                    onResult?.invoke(true, "TFT Map Route started: $destLabel (${TftMapMathAndParser.formatDistanceMeters(snap.remainingTotalMeters)})")
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoadingRoute = false,
                        statusMessage = "OSRM offline — showing destination pin"
                    )
                    onMapInvalidated?.invoke()
                    onResult?.invoke(false, "Could not fetch route (check mobile data)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Route resolution failed: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoadingRoute = false,
                    statusMessage = "Route lookup error"
                )
                onMapInvalidated?.invoke()
                onResult?.invoke(false, "Route lookup failed: ${e.message}")
            }
        }
    }

    private fun computeOsrmRoute(origin: GeoPoint, dest: GeoPoint, label: String): ActiveRoutePlan? {
        val url = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline6&steps=true",
            origin.lng, origin.lat, dest.lng, dest.lat
        )
        val raw = CellularNetworkHttp.getText(context, url, connectTimeoutMs = 5000, readTimeoutMs = 6500)
            ?: return null
        val route = TftMapMathAndParser.parseOsrmRouteResponse(raw, label, origin, dest) ?: return null
        activeRoutePlan = route
        _uiState.value = _uiState.value.copy(
            routeActive = true,
            isLoadingRoute = false,
            destinationLabel = label,
            remainingDistanceText = TftMapMathAndParser.formatDistanceMeters(route.totalDistanceMeters.roundToInt()),
            remainingEtaText = TftMapMathAndParser.formatEtaDuration(route.totalDurationSeconds.roundToInt()),
            statusMessage = ""
        )
        onMapInvalidated?.invoke()
        return route
    }

    private fun geocodeQuery(query: String): Pair<Double, Double>? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // Try Photon first (fast OSM geocoder), fallback to Nominatim
        val photonUrl = "https://photon.komoot.io/api/?q=$encoded&limit=1"
        CellularNetworkHttp.getText(context, photonUrl, connectTimeoutMs = 4000, readTimeoutMs = 5000)?.let { raw ->
            try {
                val features = JSONObject(raw).optJSONArray("features")
                if (features != null && features.length() > 0) {
                    val coords = features.getJSONObject(0).optJSONObject("geometry")?.optJSONArray("coordinates")
                    if (coords != null && coords.length() >= 2) {
                        val ln = coords.getDouble(0)
                        val la = coords.getDouble(1)
                        if (NavigationIntentLauncher.hasValidCoordinates(la, ln)) return la to ln
                    }
                }
            } catch (_: Exception) {
            }
        }
        val nominatimUrl = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1"
        CellularNetworkHttp.getText(context, nominatimUrl, connectTimeoutMs = 4500, readTimeoutMs = 5500)?.let { raw ->
            try {
                val arr = JSONArray(raw)
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    val la = o.optString("lat").toDoubleOrNull()
                    val ln = o.optString("lon").toDoubleOrNull()
                    if (la != null && ln != null && NavigationIntentLauncher.hasValidCoordinates(la, ln)) {
                        return la to ln
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Renders the full-bleed `800×480` landscape Maps-only framebuffer directly onto [canvas]
     * without requiring `MediaProjection` or phone screen-on state.
     */
    fun renderMapFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        daylightMode: Boolean,
        isStationary: Boolean,
        batteryPct: Int,
        clockText: String,
        activeNavStep: ParsedNavigationStep?,
        incomingCaller: String?,
        whatsAppSender: String?,
        toastBanner: String?
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        bgPaint.color = if (daylightMode) Color.parseColor("#E8ECEF") else Color.parseColor("#0D1117")
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val state = _uiState.value
        val step = activeNavStep ?: lastRouteSnapshot?.nextStep
        val zoom = state.zoomLevel.coerceIn(12, 18)

        // Offset marker slightly to the right/lower-center when a top-left TBT card is visible
        val anchorX = if (step != null) w * 0.60f else w * 0.50f
        val anchorY = if (state.headingUp) h * 0.62f else h * 0.50f

        if (state.hasGpsFix || (state.lat != 0.0 && state.lng != 0.0)) {
            drawMapTilesAndRoute(
                canvas = canvas,
                w = w,
                h = h,
                anchorX = anchorX,
                anchorY = anchorY,
                centerLat = state.lat,
                centerLng = state.lng,
                bearingDeg = if (state.headingUp) state.bearingDeg else 0f,
                zoom = zoom,
                darkMode = !daylightMode
            )
        } else {
            drawGridFallback(canvas, w, h, daylightMode)
        }

        // Top-Left compact Turn-by-Turn overlay card (only covers ~32% x 30%, keeping map front & center)
        if (step != null) {
            drawTbtCornerCard(canvas, step, daylightMode)
        } else if (state.statusMessage.isNotBlank()) {
            drawStatusPill(canvas, state.statusMessage, daylightMode)
        }

        // Top-Right Call / WhatsApp alert pill if active
        if (!incomingCaller.isNullOrBlank()) {
            drawTopRightAlertPill(canvas, "CALL: $incomingCaller", Color.parseColor("#DC2626"))
        } else if (!whatsAppSender.isNullOrBlank()) {
            drawTopRightAlertPill(canvas, "WA: $whatsAppSender", Color.parseColor("#059669"))
        }

        // Right-side map control pills (- / + / North-Up) aligned neatly without clutter
        drawRightMapControls(canvas, w, daylightMode, state.headingUp)

        // Bottom telemetry ribbon (Speed • ETA/DTE • Clock • Motion)
        drawBottomTelemetryStrip(canvas, w, h, daylightMode, state, clockText, batteryPct, isStationary)

        if (!toastBanner.isNullOrBlank()) {
            rectTmp.set(w * 0.25f, 12f, w * 0.75f, 44f)
            cardPaint.color = Color.parseColor("#E61D4ED8")
            canvas.drawRoundRect(rectTmp, 16f, 16f, cardPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 15f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(toastBanner.take(36), rectTmp.centerX(), rectTmp.centerY() + 5f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawMapTilesAndRoute(
        canvas: Canvas,
        w: Float,
        h: Float,
        anchorX: Float,
        anchorY: Float,
        centerLat: Double,
        centerLng: Double,
        bearingDeg: Float,
        zoom: Int,
        darkMode: Boolean
    ) {
        val (centerWx, centerWy) = TftMapMathAndParser.latLngToWorldPixel(centerLat, centerLng, zoom)
        canvas.save()
        canvas.translate(anchorX, anchorY)
        if (bearingDeg != 0f) {
            canvas.rotate(-bearingDeg)
        }

        // When rotated, cover bounding radius diagonal so corners never show un-tiled holes
        val halfDiag = sqrt((w * w + h * h).toDouble()).toFloat() * 0.65f
        val minWx = centerWx - halfDiag
        val maxWx = centerWx + halfDiag
        val minWy = centerWy - halfDiag
        val maxWy = centerWy + halfDiag

        val minTx = floor(minWx / 256.0).toInt()
        val maxTx = floor(maxWx / 256.0).toInt()
        val minTy = floor(minWy / 256.0).toInt()
        val maxTy = floor(maxWy / 256.0).toInt()

        tilePaint.colorFilter = if (darkMode) nightTileFilter else dayTileFilter
        var anyTileMissing = false
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                val bmp = tileCache.getOrRequestTile(zoom, tx, ty) {
                    onMapInvalidated?.invoke()
                }
                val dx = (tx * 256.0 - centerWx).toFloat()
                val dy = (ty * 256.0 - centerWy).toFloat()
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, dx, dy, tilePaint)
                } else {
                    anyTileMissing = true
                }
            }
        }
        if (anyTileMissing) {
            gridPaint.color = if (darkMode) Color.parseColor("#243044") else Color.parseColor("#CBD5E1")
            var gx = floor(minWx / 64.0) * 64.0
            while (gx <= maxWx) {
                val sx = (gx - centerWx).toFloat()
                canvas.drawLine(sx, -halfDiag, sx, halfDiag, gridPaint)
                gx += 64.0
            }
            var gy = floor(minWy / 64.0) * 64.0
            while (gy <= maxWy) {
                val sy = (gy - centerWy).toFloat()
                canvas.drawLine(-halfDiag, sy, halfDiag, sy, gridPaint)
                gy += 64.0
            }
        }

        // Draw active OSRM route polyline
        val route = activeRoutePlan
        if (route != null && route.polyline.size >= 2) {
            routePath.reset()
            for (i in route.polyline.indices) {
                val pt = route.polyline[i]
                val (px, py) = TftMapMathAndParser.latLngToWorldPixel(pt.lat, pt.lng, zoom)
                val sx = (px - centerWx).toFloat()
                val sy = (py - centerWy).toFloat()
                if (i == 0) routePath.moveTo(sx, sy) else routePath.lineTo(sx, sy)
            }
            canvas.drawPath(routePath, routeCasingPaint)
            canvas.drawPath(routePath, routeFillPaint)

            val (destX, destY) = TftMapMathAndParser.latLngToWorldPixel(route.destination.lat, route.destination.lng, zoom)
            val dsx = (destX - centerWx).toFloat()
            val dsy = (destY - centerWy).toFloat()
            canvas.drawCircle(dsx, dsy, 11f, markerBorderPaint)
            canvas.drawCircle(dsx, dsy, 9f, destPinPaint)
        }

        // Rotate bike cursor back to actual heading relative to map
        canvas.save()
        if (bearingDeg == 0f) {
            canvas.rotate(_uiState.value.bearingDeg)
        }
        canvas.drawCircle(0f, 0f, 20f, markerHaloPaint)
        arrowPath.reset()
        arrowPath.moveTo(0f, -15f)
        arrowPath.lineTo(11f, 12f)
        arrowPath.lineTo(0f, 6f)
        arrowPath.lineTo(-11f, 12f)
        arrowPath.close()
        canvas.drawPath(arrowPath, markerPaint)
        canvas.drawPath(arrowPath, markerBorderPaint)
        canvas.restore()

        canvas.restore()
    }

    private fun drawGridFallback(canvas: Canvas, w: Float, h: Float, daylight: Boolean) {
        gridPaint.color = if (daylight) Color.parseColor("#D1D5DB") else Color.parseColor("#1E293B")
        for (x in 0..800 step 40) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h, gridPaint)
        for (y in 0..480 step 40) canvas.drawLine(0f, y.toFloat(), w, y.toFloat(), gridPaint)
        canvas.drawCircle(w * 0.5f, h * 0.55f, 18f, markerHaloPaint)
        canvas.drawCircle(w * 0.5f, h * 0.55f, 8f, markerPaint)
    }

    private fun drawTbtCornerCard(canvas: Canvas, step: ParsedNavigationStep, daylight: Boolean) {
        rectTmp.set(16f, 14f, 326f, 152f)
        cardPaint.color = if (daylight) Color.parseColor("#F20F172A") else Color.parseColor("#EA0D1B2A")
        borderPaint.color = Color.parseColor("#38BDF8")
        canvas.drawRoundRect(rectTmp, 18f, 18f, cardPaint)
        canvas.drawRoundRect(rectTmp, 18f, 18f, borderPaint)

        // Turn arrow badge on left
        drawManeuverVector(canvas, step.maneuver, 58f, 68f, 24f, Color.parseColor("#38BDF8"))

        val distStr = step.rawDistanceText.ifBlank { TftMapMathAndParser.formatDistanceMeters(step.distanceMeters) }
        textPaint.color = Color.WHITE
        textPaint.textSize = 30f
        canvas.drawText(distStr, 102f, 56f, textPaint)

        subTextPaint.color = Color.parseColor("#CBD5E1")
        subTextPaint.textSize = 17f
        val street = step.streetName.ifBlank { step.maneuver.description }
        val streetLines = wrapMaxTwoLines(street, 19)
        canvas.drawText(streetLines.first(), 102f, 84f, subTextPaint)
        if (streetLines.size > 1) {
            canvas.drawText(streetLines[1], 102f, 106f, subTextPaint)
        }

        val etaBadge = if (step.etaHour > 0) {
            val ampm = if (step.etaIsAm) "AM" else "PM"
            String.format(Locale.US, "ETA %d:%02d %s", step.etaHour, step.etaMinute, ampm)
        } else {
            lastRouteSnapshot?.let { TftMapMathAndParser.formatEtaDuration(it.remainingDurationSeconds) }
                ?: "Active Navigation"
        }
        textPaint.color = Color.parseColor("#34D399")
        textPaint.textSize = 15f
        canvas.drawText(etaBadge, 28f, 134f, textPaint)
    }

    private fun drawStatusPill(canvas: Canvas, message: String, daylight: Boolean) {
        rectTmp.set(16f, 14f, 340f, 60f)
        cardPaint.color = if (daylight) Color.parseColor("#E61E293B") else Color.parseColor("#DF111827")
        borderPaint.color = Color.parseColor("#475569")
        canvas.drawRoundRect(rectTmp, 14f, 14f, cardPaint)
        canvas.drawRoundRect(rectTmp, 14f, 14f, borderPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 15f
        canvas.drawText(message.take(34), 30f, 43f, textPaint)
    }

    private fun drawTopRightAlertPill(canvas: Canvas, label: String, colorInt: Int) {
        rectTmp.set(470f, 14f, 784f, 58f)
        cardPaint.color = colorInt
        canvas.drawRoundRect(rectTmp, 14f, 14f, cardPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 17f
        canvas.drawText(label.take(28), 486f, 42f, textPaint)
    }

    private fun drawRightMapControls(
        canvas: Canvas,
        w: Float,
        daylight: Boolean,
        headingUp: Boolean
    ) {
        val pillBg = if (daylight) Color.parseColor("#E6FFFFFF") else Color.parseColor("#DD0F172A")
        val pillFg = if (daylight) Color.parseColor("#0F172A") else Color.WHITE
        val pillStroke = if (daylight) Color.parseColor("#94A3B8") else Color.parseColor("#334155")

        val btnLabels = arrayOf("+", "-", if (headingUp) "HDG" else "N↑")
        for (i in btnLabels.indices) {
            val top = 140f + i * 56f
            rectTmp.set(w - 64f, top, w - 14f, top + 46f)
            cardPaint.color = pillBg
            borderPaint.color = pillStroke
            canvas.drawRoundRect(rectTmp, 12f, 12f, cardPaint)
            canvas.drawRoundRect(rectTmp, 12f, 12f, borderPaint)
            textPaint.color = pillFg
            textPaint.textSize = if (i == 2) 13f else 22f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(btnLabels[i], rectTmp.centerX(), rectTmp.centerY() + 6f, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawBottomTelemetryStrip(
        canvas: Canvas,
        w: Float,
        h: Float,
        daylight: Boolean,
        state: TftLiveMapUiState,
        clockText: String,
        batteryPct: Int,
        isStationary: Boolean
    ) {
        rectTmp.set(16f, h - 54f, w - 16f, h - 10f)
        cardPaint.color = if (daylight) Color.parseColor("#F00F172A") else Color.parseColor("#E60B1120")
        borderPaint.color = Color.parseColor("#334155")
        canvas.drawRoundRect(rectTmp, 14f, 14f, cardPaint)
        canvas.drawRoundRect(rectTmp, 14f, 14f, borderPaint)

        val spd = state.speedKmh.roundToInt()
        textPaint.color = Color.parseColor("#38BDF8")
        textPaint.textSize = 20f
        canvas.drawText("$spd km/h", 32f, h - 25f, textPaint)

        val midLabel = when {
            state.routeActive && state.destinationLabel.isNotBlank() -> {
                val eta = state.remainingEtaText.ifBlank { "--" }
                val rem = state.remainingDistanceText.ifBlank { "--" }
                "$rem • $eta • ${state.destinationLabel.take(18)}"
            }
            state.hasGpsFix -> "MAPS LIVE  •  Z${state.zoomLevel}"
            else -> "MAPS STANDALONE  •  GPS SEARCHING"
        }
        subTextPaint.color = Color.parseColor("#E2E8F0")
        subTextPaint.textSize = 15f
        canvas.drawText(midLabel, 150f, h - 26f, subTextPaint)

        textPaint.color = Color.parseColor("#CBD5E1")
        textPaint.textSize = 14f
        textPaint.textAlign = Paint.Align.RIGHT
        val parkLabel = if (isStationary) "PARK" else "RIDE"
        canvas.drawText("$parkLabel • ${batteryPct}% • $clockText", w - 30f, h - 26f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawManeuverVector(canvas: Canvas, maneuver: Maneuver, cx: Float, cy: Float, r: Float, color: Int) {
        TftManeuverVectorDrawer.draw(
            canvas = canvas,
            maneuver = maneuver,
            cx = cx,
            cy = cy,
            s = r * 0.8f,
            strokePaint = arrowPaint,
            fillPaint = cardPaint,
            path = arrowPath,
            arcRect = rectTmp,
            primaryColor = color,
            secondaryColor = Color.WHITE
        )
    }

    private fun wrapMaxTwoLines(text: String, maxCharsPerLine: Int): List<String> {
        val clean = text.trim()
        if (clean.length <= maxCharsPerLine) return listOf(clean)
        val splitIdx = clean.lastIndexOf(' ', maxCharsPerLine).takeIf { it > 4 } ?: maxCharsPerLine
        val l1 = clean.substring(0, splitIdx).trim()
        val l2 = clean.substring(splitIdx).trim().take(maxCharsPerLine)
        return listOf(l1, l2)
    }
}

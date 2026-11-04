package com.pulsarnav.app.parser

import android.os.Bundle
import com.pulsarnav.app.model.Maneuver
import java.util.Calendar
import java.util.regex.Pattern

data class ParsedNavigationStep(
    val maneuver: Maneuver,
    val distanceMeters: Int,
    val totalDistanceMeters: Int,
    val streetName: String,
    val rawDistanceText: String,
    val etaHour: Int,
    val etaMinute: Int,
    val etaIsAm: Boolean
)

object GoogleMapsNotificationParser {

    private val DIST_REGEX = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(km|mi|ft|yd|m)\\b")
    private val CLOCK_REGEX = Pattern.compile("(\\d{1,2}):(\\d{2})(?:\\s*([ap]m))?", Pattern.CASE_INSENSITIVE)
    private val ETA_MIN_REGEX = Pattern.compile("(\\d+)\\s*min", Pattern.CASE_INSENSITIVE)
    private val ETA_HR_REGEX = Pattern.compile("(\\d+)\\s*(?:hr|hour)", Pattern.CASE_INSENSITIVE)

    fun parse(
        title: String?,
        text: String?,
        subtext: String?,
        primaryInfo: String? = null,
        secondaryInfo: String? = null,
        progressMax: Int = -1,
        iconObj: Any? = null
    ): ParsedNavigationStep? {
        val sTitle = title?.trim().orEmpty()
        val sText = text?.trim().orEmpty()
        val sSubtext = subtext?.trim().orEmpty()
        val sPrimaryInfo = primaryInfo?.trim().orEmpty()
        val sSecondaryInfo = secondaryInfo?.trim().orEmpty()

        if (sTitle.isBlank() && sText.isBlank() && sSubtext.isBlank() && sPrimaryInfo.isBlank() && sSecondaryInfo.isBlank()) {
            return null
        }
        if (sTitle.isEmpty() && sSubtext.isEmpty() && sText.contains("rerout", ignoreCase = true)) {
            return null // Rerouting
        }

        // 1. Step Distance (check ongoing activity primaryInfo first, then title, then text)
        var stepDist = parseDistanceMeters(sPrimaryInfo)
        if (stepDist < 0) {
            stepDist = parseDistanceMeters(sTitle)
        }
        if (stepDist < 0) {
            stepDist = parseDistanceMeters(sText)
        }
        stepDist = stepDist.coerceAtLeast(0)

        // 2. Total Trip Distance (check Android progressMax direct meters first, then subtext)
        var totalDist = if (progressMax > 0) progressMax else parseDistanceMeters(sSubtext)
        if (totalDist < 0) {
            totalDist = stepDist.coerceAtLeast(0)
        }

        // 3. Maneuver Classification
        val fullContent = "$sTitle $sText $sSubtext $sSecondaryInfo".lowercase()
        var classifiedManeuver = classifyFromText(fullContent)

        if (classifiedManeuver == null && iconObj != null) {
            val match = ManeuverMatcher.match(iconObj)
            if (match != null && match.score >= 0.60) {
                classifiedManeuver = match.maneuver
            }
        }
        val finalManeuver = classifiedManeuver ?: Maneuver.STRAIGHT

        // 4. Street / Road Name
        val cleanStreet = if (sSecondaryInfo.isNotBlank()) {
            cleanStreetName(sSecondaryInfo, "")
        } else {
            cleanStreetName(sText, sTitle)
        }

        // 5. ETA Time / Clock
        val clock = parseEtaClock(sSubtext)
        val etaHour: Int
        val etaMinute: Int
        val etaIsAm: Boolean

        if (clock != null) {
            etaHour = clock.hour
            etaMinute = clock.minute
            etaIsAm = clock.isAm
        } else {
            val cal = Calendar.getInstance()
            val totalSeconds = parseEtaSeconds(sSubtext)
            if (totalSeconds > 0) {
                cal.add(Calendar.SECOND, totalSeconds)
            }
            etaIsAm = (cal.get(Calendar.AM_PM) == Calendar.AM)
            var h = cal.get(Calendar.HOUR)
            if (h == 0) h = 12
            etaHour = h
            etaMinute = cal.get(Calendar.MINUTE)
        }

        val rawDist = when {
            sPrimaryInfo.isNotBlank() -> sPrimaryInfo
            DIST_REGEX.matcher(sTitle).find() -> sTitle
            DIST_REGEX.matcher(sText).find() -> sText
            else -> "${stepDist} m"
        }

        return ParsedNavigationStep(
            maneuver = finalManeuver,
            distanceMeters = stepDist,
            totalDistanceMeters = totalDist,
            streetName = cleanStreet,
            rawDistanceText = rawDist,
            etaHour = etaHour,
            etaMinute = etaMinute,
            etaIsAm = etaIsAm
        )
    }

    private fun classifyFromText(s: String): Maneuver? {
        if (s.isEmpty()) return null

        if (s.contains("destination") || s.contains("arrived") || s.contains("reached")) {
            return Maneuver.DESTINATION_REACHED
        }

        val hasDirection = s.contains("turn left") || s.contains("turn right") ||
                s.contains("left onto") || s.contains("right onto") ||
                s.contains("keep left") || s.contains("keep right") ||
                s.contains("slight") || s.contains("sharp") ||
                s.contains("roundabout") || s.contains("rotary") ||
                s.contains("fork") || s.contains("merge") ||
                s.contains("u-turn") || s.contains("uturn") ||
                s.contains("ramp") || s.contains("head north") ||
                s.contains("head south") || s.contains("head east") ||
                s.contains("head west") || s.contains("head toward") ||
                s.contains("continue straight") || s.contains("continue onto") ||
                s.contains("stay on") ||
                // Waze patterns: "turn left in", "turn right in", "exit left", "exit right"
                s.contains("exit left") || s.contains("exit right") ||
                s.contains("at the roundabout") || s.contains("take the") ||
                s.contains("in left") || s.contains("in right")  // e.g. "turn in left"

        if (!hasDirection) return null

        return when {
            s.contains("roundabout") || s.contains("rotary") || s.contains("at the roundabout") -> {
                if (s.contains("right")) Maneuver.ROUNDABOUT_RIGHT else Maneuver.ROUNDABOUT_LEFT
            }
            s.contains("u-turn") || s.contains("uturn") -> {
                if (s.contains("right")) Maneuver.U_TURN_RIGHT else Maneuver.U_TURN_LEFT
            }
            s.contains("sharp left") -> Maneuver.TURN_SHARP_LEFT
            s.contains("sharp right") -> Maneuver.TURN_SHARP_RIGHT
            s.contains("slight left") || s.contains("bear left") -> Maneuver.TURN_SLIGHT_LEFT
            s.contains("slight right") || s.contains("bear right") -> Maneuver.TURN_SLIGHT_RIGHT
            s.contains("keep left") -> Maneuver.KEEP_LEFT
            s.contains("keep right") -> Maneuver.KEEP_RIGHT
            s.contains("fork") && s.contains("left") -> Maneuver.FORK_LEFT
            s.contains("fork") && s.contains("right") -> Maneuver.FORK_RIGHT
            s.contains("exit left") || (s.contains("ramp") && s.contains("left")) -> Maneuver.RAMP_LEFT
            s.contains("exit right") || (s.contains("ramp") && s.contains("right")) -> Maneuver.RAMP_RIGHT
            s.contains("turn left") || s.contains("left onto") || s.contains("take a left") ||
                    s.contains("in left") -> Maneuver.TURN_LEFT
            s.contains("turn right") || s.contains("right onto") || s.contains("take a right") ||
                    s.contains("in right") -> Maneuver.TURN_RIGHT
            s.contains("head north") || s.contains("head south") || s.contains("head east") ||
                    s.contains("head west") || s.contains("head toward") ||
                    s.contains("continue straight") || s.contains("continue onto") ||
                    s.contains("stay on") -> Maneuver.STRAIGHT
            s.contains("merge") -> Maneuver.MERGE
            else -> null
        }
    }

    private fun parseDistanceMeters(s: String): Int {
        if (s.isEmpty()) return -1
        val m = DIST_REGEX.matcher(s)
        if (!m.find()) return -1

        val numStr = m.group(1)?.replace(',', '.') ?: return -1
        val num = numStr.toDoubleOrNull() ?: return -1
        val unit = m.group(2)?.lowercase() ?: return -1

        return when (unit) {
            "m" -> num.toInt()
            "km" -> (num * 1000.0).toInt()
            "mi" -> (num * 1609.34).toInt()
            "ft" -> (num * 0.3048).toInt()
            "yd" -> (num * 0.9144).toInt()
            else -> -1
        }
    }

    private data class EtaClock(val hour: Int, val minute: Int, val isAm: Boolean)

    private fun parseEtaClock(s: String): EtaClock? {
        if (s.isEmpty()) return null
        val m = CLOCK_REGEX.matcher(s)
        if (!m.find()) return null

        var h = m.group(1)?.toIntOrNull() ?: return null
        val min = m.group(2)?.toIntOrNull() ?: return null
        val ampm = m.group(3)?.lowercase()

        val isAm = when (ampm) {
            "am" -> true
            "pm" -> false
            else -> {
                // 24-hour fallback
                val am = h < 12
                if (h == 0) h = 12
                else if (h > 12) h -= 12
                return EtaClock(h, min, am)
            }
        }
        if (h == 0) h = 12
        return EtaClock(h, min, isAm)
    }

    private fun parseEtaSeconds(s: String): Int {
        if (s.isEmpty()) return -1
        var sec = 0
        var found = false

        val mHr = ETA_HR_REGEX.matcher(s)
        if (mHr.find()) {
            val hr = mHr.group(1)?.toIntOrNull() ?: 0
            sec += hr * 3600
            found = true
        }

        val mMin = ETA_MIN_REGEX.matcher(s)
        if (mMin.find()) {
            val min = mMin.group(1)?.toIntOrNull() ?: 0
            sec += min * 60
            found = true
        }

        return if (found) sec else -1
    }

    private fun cleanStreetName(text: String, title: String): String {
        var s = text.trim()
        if (s.isBlank()) {
            s = title.trim()
            // Strip direction prefixes like "Head north on", "Turn left onto", "Keep right at"
            s = s.replace(
                Regex("(?i)^(?:head (?:north|south|east|west|toward)|turn (?:left|right|sharp left|sharp right|slight left|slight right)|take a (?:left|right)|keep (?:left|right)|continue (?:onto|on|straight)?)\\s+(?:on|onto|toward|at)?\\s*"),
                ""
            )
        }
        if (s.startsWith("toward ", ignoreCase = true)) s = s.substring(7)
        if (s.startsWith("onto ", ignoreCase = true)) s = s.substring(5)
        if (s.startsWith("on ", ignoreCase = true)) s = s.substring(3)
        if (s.startsWith("in ", ignoreCase = true)) s = s.substring(3)

        // Clean to alphanumerics and clamp to 18 characters for bike cluster
        val sanitized = s.replace(Regex("[^0-9a-zA-Z. ]"), " ").replace(Regex("\\s+"), " ").trim()
        return if (sanitized.length > 18) sanitized.take(18).trim() else sanitized
    }
}

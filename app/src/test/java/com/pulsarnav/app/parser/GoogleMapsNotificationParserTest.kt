package com.pulsarnav.app.parser

import com.pulsarnav.app.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleMapsNotificationParserTest {

    @Test
    fun parse_commaThousandsVsCommaDecimal() {
        val thousands = GoogleMapsNotificationParser.parse(
            title = "1,200 m",
            text = "Turn left onto Outer Ring Rd",
            subtext = "15 km • 25 min"
        )
        assertNotNull(thousands)
        assertEquals(1200, thousands!!.distanceMeters)
        assertEquals(Maneuver.TURN_LEFT, thousands.maneuver)

        val euroDecimal = GoogleMapsNotificationParser.parse(
            title = "1,2 km",
            text = "Keep right toward Airport",
            subtext = "8,5 km • 12 min"
        )
        assertNotNull(euroDecimal)
        assertEquals(1200, euroDecimal!!.distanceMeters)
        assertEquals(8500, euroDecimal.totalDistanceMeters)
        assertEquals(Maneuver.KEEP_RIGHT, euroDecimal.maneuver)
    }

    @Test
    fun parse_missingDistanceDoesNotTriggerFalseArrivalOnDestinationRoad() {
        val parsed = GoogleMapsNotificationParser.parse(
            title = "Outer Ring Rd",
            text = "Turn left onto Destination Rd",
            subtext = "5 km • 10 min"
        )
        assertNotNull(parsed)
        assertEquals(Maneuver.TURN_LEFT, parsed!!.maneuver)
        assertEquals(0, parsed.distanceMeters)

        val arrived = GoogleMapsNotificationParser.parse(
            title = "You have arrived",
            text = "Your destination is on the right",
            subtext = "0 m"
        )
        assertNotNull(arrived)
        assertEquals(Maneuver.DESTINATION_REACHED, arrived!!.maneuver)
    }

    @Test
    fun parse_roundaboutExitUnknownReturnsZeroAndExplicitReturnsExit() {
        val generic = GoogleMapsNotificationParser.parse(
            title = "200 m",
            text = "Enter the roundabout",
            subtext = "3 km"
        )
        assertNotNull(generic)
        assertEquals(0, generic!!.roundaboutExit)

        val secondExit = GoogleMapsNotificationParser.parse(
            title = "200 m",
            text = "At the roundabout, take the 2nd exit onto Link Rd",
            subtext = "3 km"
        )
        assertNotNull(secondExit)
        assertEquals(2, secondExit!!.roundaboutExit)
    }

    @Test
    fun parse_roadKeywordsInSubtextDoNotSpoofManeuver() {
        val parsed = GoogleMapsNotificationParser.parse(
            title = "300 m",
            text = "Continue straight",
            subtext = "Left Turn Lane · 5 km · 14 min"
        )
        assertNotNull(parsed)
        assertEquals(Maneuver.STRAIGHT, parsed!!.maneuver)
        assertEquals(300, parsed.distanceMeters)
    }

    @Test
    fun parse_reroutingReturnsNullAndAllowsUpTo31CharStreets() {
        assertNull(GoogleMapsNotificationParser.parse(title = "", text = "Rerouting...", subtext = ""))

        val street = GoogleMapsNotificationParser.parse(
            title = "400 m Turn right",
            text = "1234567890123456789012345678901",
            subtext = "2 km · 10:45 AM"
        )
        assertNotNull(street)
        assertEquals(Maneuver.TURN_RIGHT, street!!.maneuver)
        assertEquals(10, street.etaHour)
        assertEquals(45, street.etaMinute)
        assertEquals(true, street.etaIsAm)
        assertEquals(31, street.streetName.length)
    }
}

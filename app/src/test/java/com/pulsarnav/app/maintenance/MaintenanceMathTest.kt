package com.pulsarnav.app.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaintenanceMathTest {

    @Test
    fun parseAndFormatDecimal_handlesCommaAndUsLocale() {
        assertEquals(14250.5, MaintenanceMath.parseDecimal("14250,5")!!, 0.001)
        assertEquals(3.25, MaintenanceMath.parseDecimal(" 3.25 ")!!, 0.001)
        assertNull(MaintenanceMath.parseDecimal("abc"))
        assertEquals("14250.5", MaintenanceMath.formatDecimal(14250.5))
    }

    @Test
    fun serviceRecord_getNumericCostHandlesCommasAndItemsFallback() {
        val withTotal = ServiceRecord(
            date = "10 Nov 2026",
            odoKm = 5000.0,
            title = "2nd Service",
            cost = "₹1,450"
        )
        assertEquals(1450.0, withTotal.getNumericCost(), 0.001)

        val euroDecimal = ServiceRecord(
            date = "10 Nov 2026",
            odoKm = 5000.0,
            title = "Chain Kit",
            cost = "€42,50"
        )
        assertEquals(42.5, euroDecimal.getNumericCost(), 0.001)

        val itemFallback = ServiceRecord(
            date = "10 Nov 2026",
            odoKm = 5000.0,
            title = "Oil",
            cost = "",
            items = listOf(ServiceItem("Motul", 650.0), ServiceItem("Filter", 95.0))
        )
        assertEquals(745.0, itemFallback.getNumericCost(), 0.001)
    }
}

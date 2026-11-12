package com.pulsarnav.app.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MaintenanceMathTest {

    @Test
    fun parseAndFormatDecimal_handlesCommaAndUsLocale() {
        assertEquals(14250.5, MaintenanceMath.parseDecimal("14250,5")!!, 0.001)
        assertEquals(3.25, MaintenanceMath.parseDecimal(" 3.25 ")!!, 0.001)
        assertNull(MaintenanceMath.parseDecimal("abc"))
        assertEquals("14250.5", MaintenanceMath.formatDecimal(14250.5))
    }

    @Test
    fun formatCurrencyAmount_preservesCentsAndFormatsExactIntegersCleanly() {
        assertEquals("550", MaintenanceMath.formatCurrencyAmount(550.0))
        assertEquals("125.50", MaintenanceMath.formatCurrencyAmount(125.5))
        assertEquals("99.95", MaintenanceMath.formatCurrencyAmount(99.95))
    }

    @Test
    fun defaultServiceTargetOdo_scalesWithMotorcycleMilestones() {
        assertEquals(500.0, MaintenanceMath.defaultServiceTargetOdo(350.0), 0.001)
        assertEquals(5000.0, MaintenanceMath.defaultServiceTargetOdo(750.0), 0.001)
        assertEquals(5000.0, MaintenanceMath.defaultServiceTargetOdo(3200.0), 0.001)
        assertEquals(10000.0, MaintenanceMath.defaultServiceTargetOdo(6400.0), 0.001)
        assertEquals(15000.0, MaintenanceMath.defaultServiceTargetOdo(10200.0), 0.001)
    }

    @Test
    fun gloveboxExpiryEvaluator_distinguishesExpiredExpiringValidAndUnknown() {
        val today = LocalDate.of(2026, 11, 12)

        assertEquals(
            GloveboxDocumentStatus.NO_FILE,
            GloveboxExpiryEvaluator.evaluate(hasFile = false, rawExpiry = "", today = today)
        )
        assertEquals(
            GloveboxDocumentStatus.ATTACHED_NO_EXPIRY,
            GloveboxExpiryEvaluator.evaluate(hasFile = true, rawExpiry = "", today = today)
        )
        assertEquals(
            GloveboxDocumentStatus.EXPIRED,
            GloveboxExpiryEvaluator.evaluate(hasFile = true, rawExpiry = "11 Nov 2026", today = today)
        )
        assertEquals(
            GloveboxDocumentStatus.EXPIRED,
            GloveboxExpiryEvaluator.evaluate(hasFile = false, rawExpiry = "2026-11-01", today = today)
        )
        assertEquals(
            GloveboxDocumentStatus.EXPIRING_SOON,
            GloveboxExpiryEvaluator.evaluate(hasFile = true, rawExpiry = "30 Nov 2026", today = today)
        )
        assertEquals(
            GloveboxDocumentStatus.VALID,
            GloveboxExpiryEvaluator.evaluate(hasFile = true, rawExpiry = "15 Nov 2027", today = today)
        )
        assertEquals(
            LocalDate.of(2027, 11, 30),
            GloveboxExpiryEvaluator.parseExpiryDate("Nov 2027")
        )
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

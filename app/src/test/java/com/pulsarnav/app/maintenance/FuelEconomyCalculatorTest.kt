package com.pulsarnav.app.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelEconomyCalculatorTest {

    @Test
    fun recalculateChainedStats_chainsPartialFillsIntoNextFullFill() {
        val base = FuelEntry(id = "1", timestampMs = 1_000L, odometerKm = 1000.0, liters = 8.0, totalCostInr = 800.0, calculatedKmpl = null, isFullTank = true)
        val partial = FuelEntry(id = "2", timestampMs = 2_000L, odometerKm = 1150.0, liters = 3.0, totalCostInr = 300.0, calculatedKmpl = null, isFullTank = false)
        val closingFull = FuelEntry(id = "3", timestampMs = 3_000L, odometerKm = 1350.0, liters = 7.0, totalCostInr = 700.0, calculatedKmpl = null, isFullTank = true)

        val result = FuelEconomyCalculator.recalculateChainedStats(listOf(closingFull, base, partial))
        assertEquals(listOf("3", "2", "1"), result.map { it.id })

        // Distance = 1350 - 1000 = 350 km; liters burned = 3.0 (partial) + 7.0 (closing) = 10.0L -> 35.0 km/l
        assertEquals(35.0, result[0].calculatedKmpl!!, 0.001)
        assertNull(result[1].calculatedKmpl)
        assertNull(result[2].calculatedKmpl)

        val stats = FuelEconomyCalculator.computeStats(result)
        assertEquals(35.0, stats.averageKmpl, 0.001)
        assertEquals(1800.0, stats.totalSpentInr, 0.001)
        // Cost over span excludes baseline fill (1000.0 over 350 km)
        assertEquals(1000.0 / 350.0, stats.costPerKm, 0.001)
    }

    @Test
    fun computeStats_sameOdometerReturnsZeroCostPerKm() {
        val e1 = FuelEntry(id = "1", timestampMs = 1_000L, odometerKm = 5000.0, liters = 5.0, totalCostInr = 500.0, calculatedKmpl = null, isFullTank = true)
        val e2 = FuelEntry(id = "2", timestampMs = 2_000L, odometerKm = 5000.0, liters = 5.0, totalCostInr = 500.0, calculatedKmpl = null, isFullTank = true)

        val recalculated = FuelEconomyCalculator.recalculateChainedStats(listOf(e1, e2))
        val stats = FuelEconomyCalculator.computeStats(recalculated)
        assertEquals(0.0, stats.averageKmpl, 0.001)
        assertEquals(0.0, stats.costPerKm, 0.001)
    }
}

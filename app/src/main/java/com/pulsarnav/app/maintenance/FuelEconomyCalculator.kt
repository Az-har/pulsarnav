package com.pulsarnav.app.maintenance

/**
 * Pure-Kotlin fuel economy and chained full-tank / partial-fill mileage calculator.
 */
object FuelEconomyCalculator {

    private const val MIN_VALID_KMPL = 5.0
    private const val MAX_VALID_KMPL = 120.0

    /**
     * Sorts entries newest-first while computing accurate tank-to-tank mileage across
     * full-tank and partial-fill cycles:
     * - Partial fills accumulate liters until the next full-tank fill closes the segment.
     * - The first chronological full fill acts as the odometer baseline (`calculatedKmpl = null`).
     */
    fun recalculateChainedStats(list: List<FuelEntry>): List<FuelEntry> {
        if (list.isEmpty()) return emptyList()

        val sortedAsc = list.sortedWith(compareBy<FuelEntry>({ it.timestampMs }, { it.odometerKm }))
        val computedAsc = ArrayList<FuelEntry>(sortedAsc.size)

        var lastFullOdo: Double? = null
        var accumulatedLiters = 0.0

        for (entry in sortedAsc) {
            if (!entry.isFullTank) {
                if (lastFullOdo != null && entry.liters > 0.0) {
                    accumulatedLiters += entry.liters
                }
                computedAsc.add(entry.copy(calculatedKmpl = null))
            } else {
                val baseOdo = lastFullOdo
                val dist = if (baseOdo != null) (entry.odometerKm - baseOdo).coerceAtLeast(0.0) else 0.0
                val totalSegmentLiters = accumulatedLiters + entry.liters
                val kmpl = if (baseOdo != null && dist > 1.0 && totalSegmentLiters > 0.0) {
                    dist / totalSegmentLiters
                } else {
                    null
                }
                lastFullOdo = entry.odometerKm
                accumulatedLiters = 0.0
                computedAsc.add(entry.copy(calculatedKmpl = kmpl))
            }
        }

        return computedAsc.asReversed()
    }

    /**
     * Computes summary statistics across fuel log entries.
     * Excludes the initial chronological baseline fill from cost-per-km numerator so
     * distance and fuel burned stay aligned over the same odometer span.
     */
    fun computeStats(list: List<FuelEntry>): FuelStats {
        if (list.isEmpty()) return FuelStats(0.0, 0.0, 0.0)

        val totalSpent = list.sumOf { it.totalCostInr }
        var validKmplCount = 0
        var kmplSum = 0.0

        for (e in list) {
            val kmpl = e.calculatedKmpl
            if (kmpl != null && kmpl > MIN_VALID_KMPL && kmpl < MAX_VALID_KMPL) {
                kmplSum += kmpl
                validKmplCount++
            }
        }

        val avgKmpl = if (validKmplCount > 0) kmplSum / validKmplCount else 0.0

        if (list.size < 2) {
            return FuelStats(avgKmpl, totalSpent, 0.0)
        }

        val sortedAsc = list.sortedWith(compareBy<FuelEntry>({ it.timestampMs }, { it.odometerKm }))
        val minOdo = sortedAsc.minOf { it.odometerKm }
        val maxOdo = sortedAsc.maxOf { it.odometerKm }
        val totalDistance = (maxOdo - minOdo).coerceAtLeast(0.0)
        val costOverSpan = sortedAsc.drop(1).sumOf { it.totalCostInr }
        val costPerKm = if (totalDistance > 1.0) costOverSpan / totalDistance else 0.0

        return FuelStats(avgKmpl, totalSpent, costPerKm)
    }

    /**
     * Prevents backdated fuel entries from regressing the bike's current odometer.
     */
    fun computeSyncedOdometerAfterAdd(currentBikeOdoKm: Double, newEntryOdoKm: Double): Double =
        maxOf(currentBikeOdoKm, newEntryOdoKm)

    /**
     * Syncs the odometer after editing a fuel entry:
     * - Historical or lower-than-bike edits never regress the current bike odometer.
     * - Correcting an inflated typo on the entry that set the current odometer lowers it back to the
     *   true highest fuel odometer across all remaining entries.
     */
    fun computeSyncedOdometerAfterUpdate(
        currentBikeOdoKm: Double,
        oldEntryOdoKm: Double,
        allUpdatedEntries: List<FuelEntry>
    ): Double {
        val highestFuelOdo = allUpdatedEntries.maxOfOrNull { it.odometerKm } ?: currentBikeOdoKm
        val wasTopOrMatchingBikeOdo =
            kotlin.math.abs(currentBikeOdoKm - oldEntryOdoKm) < 0.11 && oldEntryOdoKm >= highestFuelOdo
        return if (wasTopOrMatchingBikeOdo) {
            highestFuelOdo
        } else {
            maxOf(currentBikeOdoKm, highestFuelOdo)
        }
    }
}

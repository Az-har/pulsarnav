package com.pulsarnav.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingFixValidatorTest {

    @Test
    fun isAcceptableFix_acceptsFreshAccurateFixAndRejectsStaleOrCoarse() {
        val nowMs = 1_700_000_000_000L
        val nowNanos = 500_000_000_000_000L

        assertTrue(
            ParkingFixValidator.isAcceptableFix(
                fixTimeMs = nowMs - 30_000L,
                fixElapsedNanos = nowNanos - 30_000L * 1_000_000L,
                accuracyMeters = 18f,
                nowMs = nowMs,
                nowElapsedNanos = nowNanos
            )
        )

        // Reject coarse cell/Wi-Fi location > 150m
        assertFalse(
            ParkingFixValidator.isAcceptableFix(
                fixTimeMs = nowMs - 10_000L,
                fixElapsedNanos = nowNanos - 10_000L * 1_000_000L,
                accuracyMeters = 250f,
                nowMs = nowMs,
                nowElapsedNanos = nowNanos
            )
        )

        // Reject stale elapsed-time fix (> 5 min) even if wall clock looks recent
        assertFalse(
            ParkingFixValidator.isAcceptableFix(
                fixTimeMs = nowMs - 10_000L,
                fixElapsedNanos = nowNanos - (ParkingFixValidator.MAX_FIX_AGE_MS + 10_000L) * 1_000_000L,
                accuracyMeters = 12f,
                nowMs = nowMs,
                nowElapsedNanos = nowNanos
            )
        )

        // Wall-clock fallback rejects stale and future/invalid timestamps
        assertFalse(
            ParkingFixValidator.isAcceptableFix(
                fixTimeMs = nowMs - (ParkingFixValidator.MAX_FIX_AGE_MS + 1_000L),
                fixElapsedNanos = null,
                accuracyMeters = 20f,
                nowMs = nowMs,
                nowElapsedNanos = null
            )
        )
        assertFalse(
            ParkingFixValidator.isAcceptableFix(
                fixTimeMs = 0L,
                fixElapsedNanos = null,
                accuracyMeters = 20f,
                nowMs = nowMs,
                nowElapsedNanos = null
            )
        )
    }
}

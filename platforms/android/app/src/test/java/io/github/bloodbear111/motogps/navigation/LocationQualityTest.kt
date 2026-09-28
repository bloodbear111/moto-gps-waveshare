package io.github.bloodbear111.motogps.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI-facing mirror of `NavCore`'s fix acceptance rules.
 *
 * These thresholds must match `shared/nav_core/nav_core.hpp`
 * (`maximum_usable_accuracy_m = 50`, `gnss_stale_after_ms = 5000`). If the core
 * changes, this test is the tripwire that stops the UI from explaining the wrong
 * reason — or worse, from telling the rider a stale fix is live.
 */
class LocationQualityTest {

    private fun fix(
        accuracyM: Double = 5.0,
        timestampMs: Long = 10_000,
        latitudeDeg: Double = 36.6751,
        longitudeDeg: Double = 117.12795,
    ) = MotoGnssFix(
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        accuracyM = accuracyM,
        speedMps = 0.0,
        headingDeg = 0.0,
        timestampMs = timestampMs,
    )

    @Test
    fun `thresholds match the shared core configuration`() {
        assertEquals(50.0, LocationQuality.MAXIMUM_USABLE_ACCURACY_M, 0.0)
        assertEquals(5_000L, LocationQuality.STALE_AFTER_MS)
    }

    @Test
    fun `a fresh accurate fix is usable`() {
        assertTrue(LocationQuality.isUsable(fix(), nowMs = 10_500))
        assertNull(LocationQuality.describeRejection(fix(), nowMs = 10_500))
    }

    @Test
    fun `a fix beyond the accuracy budget is rejected as poor accuracy`() {
        val poor = fix(accuracyM = 120.0)
        assertFalse(LocationQuality.isUsable(poor, nowMs = 10_100))
        assertEquals(
            LocationQuality.Rejection.PoorAccuracy(120.0),
            LocationQuality.describeRejection(poor, nowMs = 10_100),
        )
    }

    @Test
    fun `an unknown accuracy is not treated as precise`() {
        val unknown = fix(accuracyM = Double.NaN)
        assertFalse(LocationQuality.isUsable(unknown, nowMs = 10_100))
        assertEquals(
            "NaN accuracy must not silently pass the threshold check",
            LocationQuality.Rejection.UnknownAccuracy,
            LocationQuality.describeRejection(unknown, nowMs = 10_100),
        )
    }

    @Test
    fun `stale and poor-accuracy are reported differently`() {
        val stale = fix(timestampMs = 1_000)
        assertEquals(
            LocationQuality.Rejection.Stale,
            LocationQuality.describeRejection(stale, nowMs = 10_000),
        )
        assertTrue(LocationQuality.isStale(stale, nowMs = 10_000))

        val poor = fix(accuracyM = 300.0)
        assertEquals(
            LocationQuality.Rejection.PoorAccuracy(300.0),
            LocationQuality.describeRejection(poor, nowMs = 10_100),
        )
    }

    @Test
    fun `a missing fix is distinct from a stale one`() {
        assertEquals(
            LocationQuality.Rejection.NoFixYet,
            LocationQuality.describeRejection(null, nowMs = 1_000),
        )
        assertTrue(LocationQuality.isStale(null, nowMs = 1_000))
    }

    @Test
    fun `non-finite coordinates are rejected`() {
        val broken = fix(latitudeDeg = Double.NaN)
        assertEquals(
            LocationQuality.Rejection.InvalidCoordinates,
            LocationQuality.describeRejection(broken, nowMs = 10_100),
        )
    }

    @Test
    fun `the staleness boundary is exclusive at five seconds`() {
        val boundary = fix(timestampMs = 0)
        assertFalse(LocationQuality.isStale(boundary, nowMs = 5_000))
        assertTrue(LocationQuality.isStale(boundary, nowMs = 5_001))
    }
}

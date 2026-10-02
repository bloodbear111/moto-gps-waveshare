package io.github.bloodbear111.motogps.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two feeds run at once now. The system feed (the phone's own GNSS) is preferred
 * while it is streaming, and the coarse network feed stays in charge when it is
 * not - which is what stops the map from being handed back and forth between a
 * moving fix and a 30 m estimate of the same moment.
 */
class LocationFixGateTest {

    private fun fix(atMs: Long) = MotoGnssFix(
        latitudeDeg = 25.6,
        longitudeDeg = 100.2,
        accuracyM = 8.0,
        speedMps = 0.0,
        headingDeg = 0.0,
        timestampMs = atMs,
    )

    @Test
    fun `the system feed always passes`() {
        val gate = LocationFixGate()

        assertTrue(gate.shouldForward(LocationFeed.System, fix(1_000L)))
        assertTrue(gate.shouldForward(LocationFeed.System, fix(1_100L)))
    }

    @Test
    fun `the network feed steps aside while the system feed is streaming`() {
        val gate = LocationFixGate(systemPreferenceWindowMs = 2_000L)

        assertTrue(gate.shouldForward(LocationFeed.System, fix(10_000L)))
        assertFalse(gate.shouldForward(LocationFeed.Amap, fix(10_500L)))
        assertFalse(gate.shouldForward(LocationFeed.Amap, fix(11_999L)))
    }

    @Test
    fun `the network feed takes over once the system feed goes quiet`() {
        val gate = LocationFixGate(systemPreferenceWindowMs = 2_000L)

        assertTrue(gate.shouldForward(LocationFeed.System, fix(10_000L)))

        // Indoors the system feed produces nothing at all, so the network feed is
        // the only thing keeping the position alive.
        assertTrue(gate.shouldForward(LocationFeed.Amap, fix(12_001L)))
    }

    @Test
    fun `without any system fix the network feed is never held back`() {
        val gate = LocationFixGate()

        assertTrue(gate.shouldForward(LocationFeed.Amap, fix(5_000L)))
        assertTrue(gate.shouldForward(LocationFeed.Amap, fix(5_100L)))
    }

    @Test
    fun `a single dropped system callback does not flap the position`() {
        val gate = LocationFixGate(systemPreferenceWindowMs = 2_000L)

        assertTrue(gate.shouldForward(LocationFeed.System, fix(20_000L)))
        // Next GNSS callback is late (2.5 s); the window is longer than one
        // interval, so the network feed does not get a turn in between.
        assertFalse(gate.shouldForward(LocationFeed.Amap, fix(21_500L)))
        assertTrue(gate.shouldForward(LocationFeed.System, fix(22_500L)))
        assertFalse(gate.shouldForward(LocationFeed.Amap, fix(23_000L)))
    }
}

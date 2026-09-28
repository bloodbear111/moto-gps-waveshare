package io.github.bloodbear111.motogps.protocol

import io.github.bloodbear111.motogps.protocol.BleSessionHeartbeatClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The v1 heartbeat must carry elapsed session time, never the phone's uptime.
 * This mirrors `testHeartbeatSendsOnlyElapsedSessionTimeAndResetsOnReconnect`
 * and `testHeartbeatElapsedTimePreservesProtocolWrapWithoutExposingUptime`.
 */
class BleSessionHeartbeatClockTest {

    @Test
    fun `reports only elapsed session time and resets on reconnect`() {
        val clock = BleSessionHeartbeatClock()
        assertNull(clock.elapsedMs(sessionId = 1, nowMs = 9_000_000))

        clock.begin(sessionId = 1, nowMs = 9_000_000)
        assertEquals(0, clock.elapsedMs(sessionId = 1, nowMs = 9_000_000))
        assertEquals(1_500, clock.elapsedMs(sessionId = 1, nowMs = 9_001_500))

        clock.reset()
        assertNull(clock.elapsedMs(sessionId = 1, nowMs = 9_002_000))

        clock.begin(sessionId = 2, nowMs = 10_000_000)
        assertNull(clock.elapsedMs(sessionId = 1, nowMs = 10_001_000))
        assertEquals(1_000, clock.elapsedMs(sessionId = 2, nowMs = 10_001_000))
    }

    @Test
    fun `preserves the 32 bit protocol wrap without leaking uptime`() {
        val clock = BleSessionHeartbeatClock()
        val uptime = 80_000_000_000L
        clock.begin(sessionId = 7, nowMs = uptime)

        assertEquals(0, clock.elapsedMs(sessionId = 7, nowMs = uptime - 1))
        // The low 32 bits wrap exactly as the wire field does.
        assertEquals(-1, clock.elapsedMs(sessionId = 7, nowMs = uptime + 0xFFFFFFFFL))
        assertEquals(1_000, clock.elapsedMs(sessionId = 7, nowMs = uptime + 0x1000003E8L))
    }

    @Test
    fun `refuses to start a session with a zero id`() {
        val clock = BleSessionHeartbeatClock()
        assertFailsWith<IllegalArgumentException> { clock.begin(0, 1_000) }
    }
}

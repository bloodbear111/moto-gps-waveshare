package io.github.bloodbear111.motogps.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors `testPumpNeverRequestsBurstAndHonoursBackpressure`: the transport must
 * request exactly one write per callback and never busy-loop when the stack is
 * still busy.
 */
class BleWritePumpPolicyTest {

    @Test
    fun `sends one frame as soon as the stack is ready`() {
        assertEquals(
            BleWritePumpPolicy.Action.SendOne,
            BleWritePumpPolicy.action(
                pendingFrameCount = 20,
                canSend = true,
                nowMs = 1_000,
                lastSendMs = 0,
                pacingMs = 15,
            ),
        )
    }

    @Test
    fun `waits out the pacing window instead of bursting`() {
        assertEquals(
            BleWritePumpPolicy.Action.Wait(milliseconds = 5),
            BleWritePumpPolicy.action(
                pendingFrameCount = 19,
                canSend = true,
                nowMs = 1_010,
                lastSendMs = 1_000,
                pacingMs = 15,
            ),
        )
    }

    @Test
    fun `stays idle while an earlier gatt operation is outstanding`() {
        assertEquals(
            BleWritePumpPolicy.Action.Idle,
            BleWritePumpPolicy.action(
                pendingFrameCount = 19,
                canSend = false,
                nowMs = 1_015,
                lastSendMs = 1_000,
                pacingMs = 15,
            ),
        )
    }

    @Test
    fun `resumes once the outstanding operation completed`() {
        assertEquals(
            BleWritePumpPolicy.Action.SendOne,
            BleWritePumpPolicy.action(
                pendingFrameCount = 19,
                canSend = true,
                nowMs = 1_015,
                lastSendMs = 1_000,
                pacingMs = 15,
            ),
        )
    }

    @Test
    fun `an empty queue never requests a write`() {
        assertEquals(
            BleWritePumpPolicy.Action.Idle,
            BleWritePumpPolicy.action(
                pendingFrameCount = 0,
                canSend = true,
                nowMs = 5_000,
                lastSendMs = 1_000,
                pacingMs = 15,
            ),
        )
    }
}

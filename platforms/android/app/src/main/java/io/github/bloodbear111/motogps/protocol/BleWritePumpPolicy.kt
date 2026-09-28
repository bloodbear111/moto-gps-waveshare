package io.github.bloodbear111.motogps.protocol

/**
 * Pacing policy for the GATT write queue, mirroring `BLEWritePumpPolicy`.
 *
 * Android's `gatt.writeCharacteristic` returns false while another operation is
 * still in flight, so the transport must never blast a burst of writes. Only
 * one frame is requested per callback, and frames from one logical message are
 * never interleaved because the queue is a plain FIFO of already-fragmented
 * values.
 */
object BleWritePumpPolicy {

    sealed interface Action {
        data object Idle : Action
        data object SendOne : Action
        data class Wait(val milliseconds: Long) : Action
    }

    /**
     * @param pendingFrameCount frames still queued.
     * @param canSend the platform signalled readiness (callback received, no
     *   outstanding operation).
     * @param nowMs monotonic clock.
     * @param lastSendMs time of the previous accepted write, 0 for none.
     * @param pacingMs minimum gap between consecutive writes.
     */
    fun action(
        pendingFrameCount: Int,
        canSend: Boolean,
        nowMs: Long,
        lastSendMs: Long,
        pacingMs: Long,
    ): Action {
        if (pendingFrameCount <= 0) return Action.Idle
        if (!canSend) return Action.Idle
        if (lastSendMs != 0L) {
            val elapsed = nowMs - lastSendMs
            if (elapsed < pacingMs) {
                return Action.Wait(pacingMs - elapsed)
            }
        }
        return Action.SendOne
    }
}

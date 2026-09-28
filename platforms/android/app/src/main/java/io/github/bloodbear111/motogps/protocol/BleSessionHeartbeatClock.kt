package io.github.bloodbear111.motogps.protocol

/**
 * Session-scoped heartbeat clock, mirroring `BLESessionHeartbeatClock`.
 *
 * The protocol's `monotonic_ms` is "this endpoint's monotonic clock for this
 * session", not the phone's uptime. Sending `SystemClock.elapsedRealtime()`
 * would leak how long the phone has been powered on and would look like a
 * freeze to the device after the 32-bit wrap. This clock therefore anchors at
 * the first observed timestamp of each session and reports only elapsed time.
 */
class BleSessionHeartbeatClock {

    private var sessionId: Int = 0
    private var anchorMs: Long = 0
    private var started: Boolean = false

    val activeSessionId: Int
        get() = sessionId

    fun begin(sessionId: Int, nowMs: Long) {
        require(sessionId != 0) { "session id must be non-zero" }
        this.sessionId = sessionId
        anchorMs = nowMs
        started = true
    }

    fun reset() {
        sessionId = 0
        anchorMs = 0
        started = false
    }

    /**
     * Elapsed milliseconds for [sessionId], truncated to the protocol's 32-bit
     * field. Returns `null` when the session is unknown so callers cannot
     * accidentally emit a heartbeat for a stale connection.
     */
    fun elapsedMs(sessionId: Int, nowMs: Long): Int? {
        if (!started || sessionId == 0 || sessionId != this.sessionId) return null
        val delta = nowMs - anchorMs
        val clamped = if (delta < 0L) 0L else delta
        return (clamped and 0xFFFFFFFFL).toInt()
    }
}

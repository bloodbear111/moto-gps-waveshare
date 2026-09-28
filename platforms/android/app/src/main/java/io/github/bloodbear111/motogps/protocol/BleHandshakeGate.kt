package io.github.bloodbear111.motogps.protocol

/**
 * Two-step v1 handshake gate, mirroring `BLEHandshakeGate` in the iOS core and
 * spec section 3.
 *
 * Sequence:
 * 1. phone sends `Starting`
 * 2. device answers `Ready` -> [Transition.SendPhoneReady]
 * 3. phone sends `Ready`
 * 4. device answers `Ready` again with identical negotiation parameters ->
 *    [Transition.ProtocolReady]
 *
 * Only after step 4 may business payloads be sent. A GATT write success or the
 * first device `Ready` is explicitly *not* sufficient.
 */
class BleHandshakeGate(localMaximumFrameSize: Int) {

    private val localMaximumFrameSize: Int = localMaximumFrameSize.coerceIn(
        MotoProtocolCodec.MINIMUM_FRAME_SIZE,
        MotoProtocolCodec.MAXIMUM_FRAME_SIZE_LIMIT,
    )

    var stage: Stage = Stage.Idle
        private set

    var sessionId: Int = 0
        private set

    var negotiatedMaximumFrameSize: Int = 0
        private set

    var negotiatedHeartbeatIntervalMs: Int = 0
        private set

    /**
     * Consecutive miss budget before the link is treated as stale. The firmware
     * uses three heartbeat intervals; we allow at least the spec's 3000 ms and
     * never less than the negotiated interval times three.
     */
    val deviceLivenessTimeoutMs: Long
        get() = maxOf(5_000L, negotiatedHeartbeatIntervalMs.toLong() * 3L)

    private var initialMinimumVersion: Int = 0
    private var initialMaximumVersion: Int = 0
    private var initialCapabilities: Int = 0
    private var initialPeerMaximumFrameSize: Int = 0
    private var initialHeartbeatIntervalMs: Int = 0

    fun begin(sessionId: Int) {
        require(sessionId != 0) { "session id must be non-zero" }
        this.sessionId = sessionId
        negotiatedMaximumFrameSize = 0
        negotiatedHeartbeatIntervalMs = 0
        initialMinimumVersion = 0
        initialMaximumVersion = 0
        initialCapabilities = 0
        initialPeerMaximumFrameSize = 0
        initialHeartbeatIntervalMs = 0
        stage = Stage.AwaitingInitialDeviceReady
    }

    @Throws(HandshakeRejected::class)
    fun acceptDeviceReady(status: DeviceStatus): Transition {
        validate(status)
        val negotiated = minOf(localMaximumFrameSize, status.maximumFrameSize)

        return when (stage) {
            Stage.AwaitingInitialDeviceReady -> {
                initialMinimumVersion = status.minimumVersion
                initialMaximumVersion = status.maximumVersion
                initialCapabilities = status.capabilities
                initialPeerMaximumFrameSize = status.maximumFrameSize
                initialHeartbeatIntervalMs = status.heartbeatIntervalMs
                negotiatedMaximumFrameSize = negotiated
                negotiatedHeartbeatIntervalMs = status.heartbeatIntervalMs
                stage = Stage.AwaitingFinalDeviceReady
                Transition.SendPhoneReady(negotiated)
            }

            Stage.AwaitingFinalDeviceReady -> {
                val consistent =
                    status.minimumVersion == initialMinimumVersion &&
                        status.maximumVersion == initialMaximumVersion &&
                        status.capabilities == initialCapabilities &&
                        status.maximumFrameSize == initialPeerMaximumFrameSize &&
                        status.heartbeatIntervalMs == initialHeartbeatIntervalMs &&
                        negotiated == negotiatedMaximumFrameSize
                if (!consistent) throw HandshakeRejected.InconsistentNegotiation
                stage = Stage.Ready
                Transition.ProtocolReady(negotiated)
            }

            Stage.Idle, Stage.Ready -> throw HandshakeRejected.UnexpectedStage
        }
    }

    fun reset() {
        stage = Stage.Idle
        sessionId = 0
        negotiatedMaximumFrameSize = 0
        negotiatedHeartbeatIntervalMs = 0
        initialMinimumVersion = 0
        initialMaximumVersion = 0
        initialCapabilities = 0
        initialPeerMaximumFrameSize = 0
        initialHeartbeatIntervalMs = 0
    }

    /** Rejects session-bearing messages outside the negotiated session. */
    @Throws(HandshakeRejected::class)
    fun validateProtocolSession(candidateSessionId: Int) {
        if (stage != Stage.Ready) throw HandshakeRejected.UnexpectedStage
        if (candidateSessionId == 0 || candidateSessionId != sessionId) {
            throw HandshakeRejected.InvalidSession
        }
    }

    @Throws(HandshakeRejected::class)
    private fun validate(status: DeviceStatus) {
        if (status.role != EndpointRole.Device.code) {
            throw HandshakeRejected.UnexpectedRole(status.role)
        }
        if (status.state != ConnectionState.Ready.code) {
            throw HandshakeRejected.UnexpectedState(status.state)
        }
        if (status.minimumVersion > PROTOCOL_VERSION ||
            status.maximumVersion < PROTOCOL_VERSION
        ) {
            throw HandshakeRejected.UnsupportedVersion(
                status.minimumVersion,
                status.maximumVersion,
            )
        }
        if (status.sessionId != sessionId) throw HandshakeRejected.InvalidSession
        val missing = MotoCapability.REQUIRED_FROM_DEVICE and status.capabilities.inv()
        if (missing != 0) throw HandshakeRejected.MissingCapabilities(missing)
        if (status.maximumFrameSize !in
            MotoProtocolCodec.MINIMUM_FRAME_SIZE..MotoProtocolCodec.MAXIMUM_FRAME_SIZE_LIMIT
        ) {
            throw HandshakeRejected.InvalidFrameSize(status.maximumFrameSize)
        }
        if (status.heartbeatIntervalMs < MINIMUM_HEARTBEAT_INTERVAL_MS) {
            throw HandshakeRejected.InvalidHeartbeat(status.heartbeatIntervalMs)
        }
    }

    enum class Stage {
        Idle,
        AwaitingInitialDeviceReady,
        AwaitingFinalDeviceReady,
        Ready,
    }

    sealed interface Transition {
        data class SendPhoneReady(val maximumFrameSize: Int) : Transition
        data class ProtocolReady(val maximumFrameSize: Int) : Transition
    }

    data class DeviceStatus(
        val role: Int,
        val state: Int,
        val minimumVersion: Int,
        val maximumVersion: Int,
        val capabilities: Int,
        val sessionId: Int,
        val maximumFrameSize: Int,
        val heartbeatIntervalMs: Int,
    )

    sealed class HandshakeRejected(message: String) : Exception(message) {
        data object InvalidSession : HandshakeRejected("BLE handshake session is invalid")
        data object UnexpectedStage : HandshakeRejected("BLE handshake message order is invalid")
        data class UnexpectedRole(val role: Int) :
            HandshakeRejected("BLE handshake role is invalid ($role)")

        data class UnexpectedState(val state: Int) :
            HandshakeRejected("device did not report Ready ($state)")

        data class UnsupportedVersion(val minimum: Int, val maximum: Int) :
            HandshakeRejected("device protocol range $minimum-$maximum excludes v1")

        data class MissingCapabilities(val missing: Int) :
            HandshakeRejected("device is missing capabilities 0x%08X".format(missing))

        data class InvalidFrameSize(val size: Int) :
            HandshakeRejected("device reported an invalid frame size ($size)")

        data class InvalidHeartbeat(val intervalMs: Int) :
            HandshakeRejected("device reported an invalid heartbeat ($intervalMs ms)")

        data object InconsistentNegotiation :
            HandshakeRejected("device Ready parameters changed between replies")
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MINIMUM_HEARTBEAT_INTERVAL_MS = 250

        fun from(status: MotoConnectionStatus): DeviceStatus = DeviceStatus(
            role = status.role,
            state = status.state,
            minimumVersion = status.minimumVersion,
            maximumVersion = status.maximumVersion,
            capabilities = status.capabilities,
            sessionId = status.sessionId,
            maximumFrameSize = status.maxFrameSize,
            heartbeatIntervalMs = status.heartbeatIntervalMs,
        )
    }
}

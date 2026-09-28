package io.github.bloodbear111.motogps.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Locks the v1 handshake semantics that the round display relies on.
 *
 * The cases mirror `ConnectionPoliciesTests.swift` on the iOS side so both phone
 * platforms refuse the same malformed negotiations.
 */
class BleHandshakeGateTest {

    private val fullDeviceCapabilities = MotoCapability.ADVERTISED_BY_PHONE

    private fun deviceStatus(
        sessionId: Int,
        capabilities: Int = fullDeviceCapabilities,
        maxFrameSize: Int = 185,
        heartbeatIntervalMs: Int = 1_000,
        role: Int = EndpointRole.Device.code,
        state: Int = ConnectionState.Ready.code,
        minimumVersion: Int = 1,
        maximumVersion: Int = 1,
    ) = BleHandshakeGate.DeviceStatus(
        role = role,
        state = state,
        minimumVersion = minimumVersion,
        maximumVersion = maximumVersion,
        capabilities = capabilities,
        sessionId = sessionId,
        maximumFrameSize = maxFrameSize,
        heartbeatIntervalMs = heartbeatIntervalMs,
    )

    @Test
    fun `requires two matching ready replies before protocol ready`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(0x12345678)

        val first = gate.acceptDeviceReady(deviceStatus(0x12345678))
        assertEquals(
            BleHandshakeGate.Transition.SendPhoneReady(maximumFrameSize = 185),
            first,
        )
        assertEquals(BleHandshakeGate.Stage.AwaitingFinalDeviceReady, gate.stage)

        val second = gate.acceptDeviceReady(deviceStatus(0x12345678))
        assertEquals(
            BleHandshakeGate.Transition.ProtocolReady(maximumFrameSize = 185),
            second,
        )
        assertEquals(BleHandshakeGate.Stage.Ready, gate.stage)
        assertEquals(185, gate.negotiatedMaximumFrameSize)
        assertEquals(1_000, gate.negotiatedHeartbeatIntervalMs)
    }

    @Test
    fun `negotiated frame size is the smaller of the two endpoints`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 20)
        gate.begin(7)
        gate.acceptDeviceReady(deviceStatus(7, maxFrameSize = 512))
        assertEquals(20, gate.negotiatedMaximumFrameSize)
    }

    @Test
    fun `second ready with different parameters is rejected`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(11)
        gate.acceptDeviceReady(deviceStatus(11, maxFrameSize = 185))

        assertFailsWith<BleHandshakeGate.HandshakeRejected.InconsistentNegotiation> {
            gate.acceptDeviceReady(deviceStatus(11, maxFrameSize = 180))
        }
        // The gate must not have advanced into a usable state.
        assertEquals(BleHandshakeGate.Stage.AwaitingFinalDeviceReady, gate.stage)
    }

    @Test
    fun `session id mismatch invalidates the handshake`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        val sessionId = 0x0BADF00D
        gate.begin(sessionId)
        assertFailsWith<BleHandshakeGate.HandshakeRejected.InvalidSession> {
            gate.acceptDeviceReady(deviceStatus(sessionId + 1))
        }
    }

    @Test
    fun `zero session id cannot start a handshake`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        assertFailsWith<IllegalArgumentException> { gate.begin(0) }
    }

    @Test
    fun `device that never reaches ready is rejected`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(3)
        assertFailsWith<BleHandshakeGate.HandshakeRejected.UnexpectedState> {
            gate.acceptDeviceReady(
                deviceStatus(3, state = ConnectionState.Starting.code),
            )
        }
    }

    @Test
    fun `phone role in a device reply is rejected`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(4)
        assertFailsWith<BleHandshakeGate.HandshakeRejected.UnexpectedRole> {
            gate.acceptDeviceReady(deviceStatus(4, role = EndpointRole.Phone.code))
        }
    }

    @Test
    fun `device whose version range excludes v1 is rejected`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(5)
        assertFailsWith<BleHandshakeGate.HandshakeRejected.UnsupportedVersion> {
            gate.acceptDeviceReady(deviceStatus(5, minimumVersion = 2, maximumVersion = 3))
        }
    }

    @Test
    fun `device missing navigation or ack capability is rejected`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(6)
        val missingNavigation = MotoCapability.REQUIRED_FROM_DEVICE and
            MotoCapability.NAVIGATION.inv()
        val failure = assertFailsWith<BleHandshakeGate.HandshakeRejected.MissingCapabilities> {
            gate.acceptDeviceReady(deviceStatus(6, capabilities = missingNavigation))
        }
        assertEquals(MotoCapability.NAVIGATION, failure.missing)
    }

    @Test
    fun `map scene capability is optional but every required bit is enforced`() {
        // A device without CapabilityMapScene must still be able to navigate.
        val withoutMapScene = fullDeviceCapabilities and MotoCapability.MAP_SCENE.inv()
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(9)
        gate.acceptDeviceReady(deviceStatus(9, capabilities = withoutMapScene))
        gate.acceptDeviceReady(deviceStatus(9, capabilities = withoutMapScene))
        assertEquals(BleHandshakeGate.Stage.Ready, gate.stage)
    }

    @Test
    fun `out of range frame size and heartbeat are rejected`() {
        val tooSmall = BleHandshakeGate(localMaximumFrameSize = 512)
        tooSmall.begin(12)
        assertFailsWith<BleHandshakeGate.HandshakeRejected.InvalidFrameSize> {
            tooSmall.acceptDeviceReady(deviceStatus(12, maxFrameSize = 12))
        }

        val tooLarge = BleHandshakeGate(localMaximumFrameSize = 512)
        tooLarge.begin(13)
        assertFailsWith<BleHandshakeGate.HandshakeRejected.InvalidFrameSize> {
            tooLarge.acceptDeviceReady(deviceStatus(13, maxFrameSize = 513))
        }

        val slowHeartbeat = BleHandshakeGate(localMaximumFrameSize = 512)
        slowHeartbeat.begin(14)
        assertFailsWith<BleHandshakeGate.HandshakeRejected.InvalidHeartbeat> {
            slowHeartbeat.acceptDeviceReady(deviceStatus(14, heartbeatIntervalMs = 249))
        }
    }

    @Test
    fun `business payloads are gated until both ready replies matched`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(21)
        assertFailsWith<BleHandshakeGate.HandshakeRejected.UnexpectedStage> {
            gate.validateProtocolSession(21)
        }
        gate.acceptDeviceReady(deviceStatus(21))
        gate.acceptDeviceReady(deviceStatus(21))
        gate.validateProtocolSession(21)

        assertFailsWith<BleHandshakeGate.HandshakeRejected.InvalidSession> {
            gate.validateProtocolSession(22)
        }
    }

    @Test
    fun `liveness timeout is at least three heartbeat intervals`() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 512)
        gate.begin(31)
        gate.acceptDeviceReady(deviceStatus(31, heartbeatIntervalMs = 1_000))
        assertEquals(5_000L, gate.deviceLivenessTimeoutMs)

        val slower = BleHandshakeGate(localMaximumFrameSize = 512)
        slower.begin(32)
        slower.acceptDeviceReady(deviceStatus(32, heartbeatIntervalMs = 2_000))
        assertEquals(6_000L, slower.deviceLivenessTimeoutMs)
    }

    @Test
    fun `frame size helper follows the spec mtu rule`() {
        assertEquals(20, MotoProtocolCodec.frameSizeForAttMtu(23))
        assertEquals(185, MotoProtocolCodec.frameSizeForAttMtu(188))
        assertEquals(512, MotoProtocolCodec.frameSizeForAttMtu(517))
        assertEquals(13, MotoProtocolCodec.frameSizeForAttMtu(10))
        assertTrue(MotoProtocolCodec.MINIMUM_FRAME_SIZE == 13)
    }
}

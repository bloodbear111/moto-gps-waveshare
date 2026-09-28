package io.github.bloodbear111.motogps.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bloodbear111.motogps.navigation.MotoNavSnapshotBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side verification that the Android build links the *same* v1 codec as
 * the firmware.
 *
 * These assertions need `libmoto_mobile.so`, so they run as instrumented tests.
 * JVM unit tests cannot load it; the policy tests that do run on the host are in
 * `src/test`.
 */
@RunWith(AndroidJUnit4::class)
class MotoProtocolGoldenInstrumentedTest {

    @Test
    fun nativeLibraryLoads() {
        MotoNativeLibrary.requireLoaded()
        assertTrue(MotoNativeLibrary.isLoaded)
    }

    @Test
    fun everyReviewedGoldenVectorPasses() {
        val checks = MotoProtocolCodec().use { it.runGoldenSelfTest() }
        assertTrue("expected several golden checks", checks.size >= 8)
        val failures = checks.filterNot { it.passed }
        assertTrue(
            "failing golden checks: " +
                failures.joinToString { "${it.name} -> ${it.detail}" },
            failures.isEmpty(),
        )
        // Guard against a silently empty fixture.
        assertTrue(checks.any { it.name == "crc16:123456789" })
        assertTrue(checks.any { it.name == "encode:connection-frame" })
        assertTrue(checks.any { it.name == "fragments:command20" })
        assertTrue(checks.any { it.name == "reassembly:missing-start" })
    }

    @Test
    fun kotlinEnumsMatchTheCompiledCppValues() {
        val raw = MotoProtocolCodec().use { it.rawEnumValues() }
        // Order is defined by MotoProtocolCodec.nativeEnumValues.
        assertEquals(EndpointRole.Phone.code, raw[0])
        assertEquals(EndpointRole.Device.code, raw[1])
        assertEquals(ConnectionState.Starting.code, raw[2])
        assertEquals(ConnectionState.Ready.code, raw[3])
        assertEquals(ConnectionState.Degraded.code, raw[4])
        assertEquals(ConnectionState.Closing.code, raw[5])

        assertEquals(NavigationState.Idle.code, raw[6])
        assertEquals(NavigationState.Acquiring.code, raw[7])
        assertEquals(NavigationState.Planning.code, raw[8])
        assertEquals(NavigationState.Navigating.code, raw[9])
        assertEquals(NavigationState.Rerouting.code, raw[10])
        assertEquals(NavigationState.Arrived.code, raw[11])

        ManeuverType.entries.forEachIndexed { index, type ->
            assertEquals("maneuver ${type.name}", type.code, raw[12 + index])
        }
        TrafficLevel.entries.forEachIndexed { index, level ->
            assertEquals("traffic ${level.name}", level.code, raw[25 + index])
        }
        NetworkState.entries.forEachIndexed { index, state ->
            assertEquals("network ${state.name}", state.code, raw[30 + index])
        }
        DisplayPage.entries.forEachIndexed { index, page ->
            assertEquals("page ${page.name}", page.code, raw[33 + index])
        }
        AckStatus.entries.forEachIndexed { index, status ->
            assertEquals("ack ${status.name}", status.code, raw[37 + index])
        }

        // DeviceCommandKind is not contiguous (music commands start at 16), so it
        // is checked one by one rather than by index.
        assertEquals(DeviceCommandKind.PageSelected.code, raw[42])
        assertEquals(DeviceCommandKind.Tap.code, raw[43])
        assertEquals(DeviceCommandKind.LongPress.code, raw[44])
        assertEquals(DeviceCommandKind.SwipeLeft.code, raw[45])
        assertEquals(DeviceCommandKind.SwipeRight.code, raw[46])
        assertEquals(DeviceCommandKind.SwipeUp.code, raw[47])
        assertEquals(DeviceCommandKind.SwipeDown.code, raw[48])
        assertEquals(DeviceCommandKind.MusicPrevious.code, raw[49])
        assertEquals(DeviceCommandKind.MusicTogglePlayback.code, raw[50])
        assertEquals(DeviceCommandKind.MusicNext.code, raw[51])
        assertEquals(DeviceCommandKind.MusicLike.code, raw[52])

        // Frame constants straight from the shared header.
        assertEquals(0xB7, raw[53])   // kFrameMagic
        assertEquals(1, raw[54])      // kProtocolVersion
        assertEquals(1, raw[55])      // kPayloadRevision
        assertEquals(10, raw[56])     // kFrameHeaderSize
        assertEquals(2, raw[57])      // kFrameCrcSize
        assertEquals(
            MotoProtocolCodec.MINIMUM_FRAME_SIZE,
            raw[56] + raw[57] + 1,
        )
        assertEquals(MotoProtocolCodec.MAXIMUM_FRAME_SIZE_LIMIT, raw[58])
        assertEquals(24, raw[59])     // kMaxRoutePointsPerChunk
        assertEquals(24, raw[60])     // kMaxMapSceneRoads
        assertEquals(192, raw[61])    // kMaxMapSceneRoadPoints
        assertEquals(16, raw[62])     // kMaxMapSceneBuildings
        assertEquals(128, raw[63])    // kMaxMapSceneBuildingPoints
        assertEquals(64, raw[64])     // kMaxTrafficSegments
        assertEquals(0xFFFF, raw[65]) // kUnknownTouchCoordinate
        assertEquals(MotoNavSnapshotBuffer.ROUTE_VIEW_CAPACITY, raw[59])
    }

    @Test
    fun gattUuidsComeFromTheSharedHeader() {
        val uuids = MotoProtocolCodec().use { it.gattUuids() }
        assertEquals(4, uuids.size)
        assertEquals(io.github.bloodbear111.motogps.ble.MotoBleUuids.SERVICE, uuids[0])
        assertEquals(io.github.bloodbear111.motogps.ble.MotoBleUuids.PHONE_TO_DEVICE, uuids[1])
        assertEquals(io.github.bloodbear111.motogps.ble.MotoBleUuids.DEVICE_TO_PHONE, uuids[2])
        assertEquals(io.github.bloodbear111.motogps.ble.MotoBleUuids.CCCD, uuids[3])
    }

    @Test
    fun navigationSnapshotRoundTripsThroughTheSharedEncoder() {
        val codec = MotoProtocolCodec(maximumFrameSize = 185)
        codec.use {
            val snapshot = MotoSnapshotInput().apply {
                state = NavigationState.Navigating.code
                network = NetworkState.Online.code
                hasDestination = true
                hasFix = true
                hasNextManeuver = true
                hasRouteView = true
                maneuver = ManeuverType.Right.code
                traffic = TrafficLevel.Slow.code
                speedDeciKph = 483
                speedLimitKph = 50
                headingCdeg = 9123
                routeToken = 0x0BADC0DE
                routeGeneration = 7
                roadName = "东长安街"
                instruction = "前方路口右转"
            }
            val frames = it.encodeNavigationSnapshot(snapshot)
            assertTrue("snapshot should fit one 185-byte frame", frames.size >= 1)
            // Decoding the produced frame back proves the byte-level round trip
            // without re-deriving offsets in the test.
            val decoded = MotoProtocolCodec(185).use { other ->
                var message: MotoInboundMessage? = null
                for (frame in frames) {
                    when (val result = other.pushDeviceFrame(frame, 1_000)) {
                        is MotoInboundResult.Complete -> message = result.message
                        else -> Unit
                    }
                }
                message
            }
            assertNotNull(decoded)
            // NavigationSnapshot is phone-to-device. A peripheral sending one is
            // outside v1, so the decoder reports it as unhandled rather than
            // inventing a device payload shape.
            val unhandled = decoded as MotoUnhandledMessage
            assertEquals(0x10, unhandled.messageType)
        }
    }
}

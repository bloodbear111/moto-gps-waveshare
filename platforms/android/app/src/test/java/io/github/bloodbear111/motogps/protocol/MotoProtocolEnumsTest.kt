package io.github.bloodbear111.motogps.protocol

import io.github.bloodbear111.motogps.protocol.AckStatus
import io.github.bloodbear111.motogps.protocol.ConnectionState
import io.github.bloodbear111.motogps.protocol.DeviceCommandKind
import io.github.bloodbear111.motogps.protocol.DisplayPage
import io.github.bloodbear111.motogps.protocol.EndpointRole
import io.github.bloodbear111.motogps.protocol.ManeuverType
import io.github.bloodbear111.motogps.protocol.MotoCapability
import io.github.bloodbear111.motogps.protocol.MotoMapBuildingClass
import io.github.bloodbear111.motogps.protocol.MotoMapRoadClass
import io.github.bloodbear111.motogps.protocol.NavigationState
import io.github.bloodbear111.motogps.protocol.NetworkState
import io.github.bloodbear111.motogps.protocol.TrafficLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every numeric code below is copied from `shared/protocol/ble-navigation-v1.md`
 * section 6, not from the Kotlin enums. The instrumented suite then checks the
 * same enums against the values the C++ compiler produced
 * (`MotoProtocolCodec.rawEnumValues`), so a wire-visible renumbering on either
 * side fails a test instead of corrupting a ride.
 */
class MotoProtocolEnumsTest {

    @Test
    fun `connection enums match the documented codes`() {
        assertEquals(1, EndpointRole.Phone.code)
        assertEquals(2, EndpointRole.Device.code)
        assertEquals(0, ConnectionState.Starting.code)
        assertEquals(1, ConnectionState.Ready.code)
        assertEquals(2, ConnectionState.Degraded.code)
        assertEquals(3, ConnectionState.Closing.code)
        assertEquals(0, AckStatus.Ok.code)
        assertEquals(1, AckStatus.Unsupported.code)
        assertEquals(2, AckStatus.InvalidState.code)
        assertEquals(3, AckStatus.Failed.code)
        assertEquals(4, AckStatus.Duplicate.code)
    }

    @Test
    fun `navigation and traffic enums match the documented codes`() {
        assertEquals(0, NavigationState.Idle.code)
        assertEquals(1, NavigationState.Acquiring.code)
        assertEquals(2, NavigationState.Planning.code)
        assertEquals(3, NavigationState.Navigating.code)
        assertEquals(4, NavigationState.Rerouting.code)
        assertEquals(5, NavigationState.Arrived.code)

        assertEquals(0, NetworkState.Offline.code)
        assertEquals(1, NetworkState.Connecting.code)
        assertEquals(2, NetworkState.Online.code)

        assertEquals(0, DisplayPage.Navigation.code)
        assertEquals(1, DisplayPage.Speed.code)
        assertEquals(2, DisplayPage.Compass.code)
        assertEquals(3, DisplayPage.Music.code)

        assertEquals(0, TrafficLevel.Unknown.code)
        assertEquals(1, TrafficLevel.FreeFlow.code)
        assertEquals(2, TrafficLevel.Slow.code)
        assertEquals(3, TrafficLevel.Congested.code)
        assertEquals(4, TrafficLevel.Severe.code)
    }

    @Test
    fun `maneuver order matches shared ManeuverType`() {
        val expected = listOf(
            "Unknown", "Continue", "SlightLeft", "Left", "SharpLeft", "UTurnLeft",
            "SlightRight", "Right", "SharpRight", "UTurnRight", "Roundabout",
            "Exit", "Arrive",
        )
        assertEquals(expected, ManeuverType.entries.map { it.name })
        ManeuverType.entries.forEachIndexed { index, type ->
            assertEquals(index, type.code)
        }
    }

    @Test
    fun `device command kinds match shared nav_ui actions`() {
        assertEquals(0, DeviceCommandKind.PageSelected.code)
        assertEquals(1, DeviceCommandKind.Tap.code)
        assertEquals(2, DeviceCommandKind.LongPress.code)
        assertEquals(3, DeviceCommandKind.SwipeLeft.code)
        assertEquals(4, DeviceCommandKind.SwipeRight.code)
        assertEquals(5, DeviceCommandKind.SwipeUp.code)
        assertEquals(6, DeviceCommandKind.SwipeDown.code)
        assertEquals(16, DeviceCommandKind.MusicPrevious.code)
        assertEquals(17, DeviceCommandKind.MusicTogglePlayback.code)
        assertEquals(18, DeviceCommandKind.MusicNext.code)
        assertEquals(19, DeviceCommandKind.MusicLike.code)
    }

    @Test
    fun `capability bits match the documented table`() {
        assertEquals(1 shl 0, MotoCapability.NAVIGATION)
        assertEquals(1 shl 1, MotoCapability.ROUTE_GEOMETRY)
        assertEquals(1 shl 2, MotoCapability.TRAFFIC)
        assertEquals(1 shl 3, MotoCapability.MEDIA_STATE)
        assertEquals(1 shl 4, MotoCapability.TOUCH_COMMANDS)
        assertEquals(1 shl 5, MotoCapability.MUSIC_COMMANDS)
        assertEquals(1 shl 6, MotoCapability.COMMAND_ACK)
        assertEquals(1 shl 7, MotoCapability.MAP_SCENE)

        // The firmware advertises 0x7F in the reviewed connection vector, which
        // excludes MapScene; the phone's required set must be a subset of that.
        val reviewedDeviceCapabilities = 0x7F
        assertEquals(
            0,
            MotoCapability.REQUIRED_FROM_DEVICE and reviewedDeviceCapabilities.inv(),
        )
    }

    @Test
    fun `map scene class codes match the shared enums`() {
        assertEquals(0, MotoMapRoadClass.MOTORWAY)
        assertEquals(1, MotoMapRoadClass.PRIMARY)
        assertEquals(2, MotoMapRoadClass.SECONDARY)
        assertEquals(3, MotoMapRoadClass.RESIDENTIAL)
        assertEquals(4, MotoMapRoadClass.SERVICE)
        assertEquals(5, MotoMapRoadClass.OTHER)

        assertEquals(0, MotoMapBuildingClass.GENERIC)
        assertEquals(1, MotoMapBuildingClass.LANDMARK)
        assertEquals(2, MotoMapBuildingClass.PARKING)
    }
}

package io.github.bloodbear111.motogps.protocol

/**
 * Kotlin mirror of the shared C++ enums.
 *
 * `code` values are asserted against the constants compiled from
 * `shared/ble_protocol` in `MotoProtocolEnumsTest`, so a change to the C++ side
 * fails the Android tests instead of silently shifting meanings on the wire.
 * Names and codes match `moto::ble::*` and `moto::nav::*`.
 */
enum class EndpointRole(val code: Int) {
    Phone(1),
    Device(2),
}

enum class ConnectionState(val code: Int) {
    Starting(0),
    Ready(1),
    Degraded(2),
    Closing(3),
}

enum class AckStatus(val code: Int) {
    Ok(0),
    Unsupported(1),
    InvalidState(2),
    Failed(3),
    Duplicate(4),
}

enum class NavigationState(val code: Int) {
    Idle(0),
    Acquiring(1),
    Planning(2),
    Navigating(3),
    Rerouting(4),
    Arrived(5),
}

enum class NetworkState(val code: Int) {
    Offline(0),
    Connecting(1),
    Online(2),
}

enum class DisplayPage(val code: Int) {
    Navigation(0),
    Speed(1),
    Compass(2),
    Music(3),
}

enum class ManeuverType(val code: Int) {
    Unknown(0),
    Continue(1),
    SlightLeft(2),
    Left(3),
    SharpLeft(4),
    UTurnLeft(5),
    SlightRight(6),
    Right(7),
    SharpRight(8),
    UTurnRight(9),
    Roundabout(10),
    Exit(11),
    Arrive(12),
}

enum class TrafficLevel(val code: Int) {
    Unknown(0),
    FreeFlow(1),
    Slow(2),
    Congested(3),
    Severe(4),
}

enum class DeviceCommandKind(val code: Int) {
    PageSelected(0),
    Tap(1),
    LongPress(2),
    SwipeLeft(3),
    SwipeRight(4),
    SwipeUp(5),
    SwipeDown(6),
    MusicPrevious(16),
    MusicTogglePlayback(17),
    MusicNext(18),
    MusicLike(19),
}

/** Capability bits exchanged during the v1 handshake. */
object MotoCapability {
    const val NAVIGATION = 1 shl 0
    const val ROUTE_GEOMETRY = 1 shl 1
    const val TRAFFIC = 1 shl 2
    const val MEDIA_STATE = 1 shl 3
    const val TOUCH_COMMANDS = 1 shl 4
    const val MUSIC_COMMANDS = 1 shl 5
    const val COMMAND_ACK = 1 shl 6
    const val MAP_SCENE = 1 shl 7

    /**
     * Device capabilities the phone refuses to navigate without. Mirrors
     * `BLEHandshakeGate.requiredDeviceCapabilities` in the iOS core and the
     * spec's "capability intersection" rule. `MAP_SCENE` is intentionally
     * optional: map scenes are an enhancement, not a navigation prerequisite.
     */
    const val REQUIRED_FROM_DEVICE =
        NAVIGATION or ROUTE_GEOMETRY or TRAFFIC or TOUCH_COMMANDS or COMMAND_ACK

    const val ADVERTISED_BY_PHONE =
        NAVIGATION or ROUTE_GEOMETRY or TRAFFIC or MEDIA_STATE or TOUCH_COMMANDS or
            MUSIC_COMMANDS or COMMAND_ACK or MAP_SCENE
}

enum class FrameFlag(val mask: Int) {
    Start(1 shl 0),
    End(1 shl 1),
    AckRequested(1 shl 2),
    Urgent(1 shl 3),
}

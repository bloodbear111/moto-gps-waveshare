package io.github.bloodbear111.motogps.protocol

/**
 * JNI mirror types.
 *
 * The native layer reads and writes these fields directly, so field names and
 * types are part of the ABI between Kotlin and C++. Keep them in sync with
 * `moto_jni.cpp` and with `proguard-rules.pro`, which keeps these classes from
 * being renamed.
 *
 * Strings that go on the wire are carried as UTF-8 [ByteArray]s. JNI's
 * modified-UTF-8 conversion would corrupt supplementary characters, and the v1
 * payload budgets are measured in UTF-8 bytes.
 */

private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

class MotoSnapshotInput {
    var state: Int = NavigationState.Idle.code
    var network: Int = NetworkState.Offline.code
    var displayPage: Int = DisplayPage.Navigation.code
    var maneuver: Int = ManeuverType.Unknown.code
    var traffic: Int = TrafficLevel.Unknown.code

    var hasDestination: Boolean = false
    var hasFix: Boolean = false
    var gnssStale: Boolean = true
    var offRoute: Boolean = false
    var hasNextManeuver: Boolean = false
    var routeRequestInFlight: Boolean = false
    var trafficRequestInFlight: Boolean = false
    var hasRouteView: Boolean = false

    var routeToken: Int = 0
    var routeGeneration: Int = 0
    var maneuverId: Int = 0
    var distanceToManeuverM: Int = 0
    var remainingDistanceM: Int = 0
    var remainingDurationS: Int = 0
    var routeProgressM: Int = 0
    var totalDistanceM: Int = 0
    var speedDeciKph: Int = 0
    var speedLimitKph: Int = 0
    var headingCdeg: Int = 0
    var accuracyDm: Int = 0
    var crossTrackDm: Int = 0
    var roundaboutExit: Int = 0

    var roadNameUtf8: ByteArray = ByteArray(0)
    var instructionUtf8: ByteArray = ByteArray(0)

    var roadName: String
        get() = roadNameUtf8.toString(Charsets.UTF_8)
        set(value) {
            roadNameUtf8 = value.utf8()
        }

    var instruction: String
        get() = instructionUtf8.toString(Charsets.UTF_8)
        set(value) {
            instructionUtf8 = value.utf8()
        }
}

/** Result of pushing one characteristic value through the shared reassembler. */
class MotoInboundBuffer {
    companion object {
        /** Return codes of `MotoProtocolCodec.pushDeviceFrame`. */
        const val STATUS_ERROR = 0
        const val STATUS_IN_PROGRESS = 1
        const val STATUS_COMPLETE = 2
        const val STATUS_DUPLICATE_FRAGMENT = 3
        const val STATUS_DUPLICATE_MESSAGE = 4

        /** `kind` values written by the native layer. */
        const val KIND_NONE = 0
        const val KIND_CONNECTION_STATUS = 1
        const val KIND_HEARTBEAT = 2
        const val KIND_ACK = 3
        const val KIND_DEVICE_COMMAND = 4
        const val KIND_UNHANDLED_MESSAGE = 5
    }

    var sequence: Int = 0
    var ackRequested: Boolean = false
    var complete: Boolean = false
    var duplicate: Boolean = false
    var errorCode: Int = 0
    var errorOffset: Int = 0
    var kind: Int = KIND_NONE
    var messageType: Int = 0

    var role: Int = 0
    var state: Int = 0
    var minimumVersion: Int = 0
    var maximumVersion: Int = 0
    var capabilities: Int = 0
    var sessionId: Int = 0
    var maxFrameSize: Int = 0
    var heartbeatIntervalMs: Int = 0

    var heartbeatMonotonicMs: Int = 0
    var heartbeatStatusFlags: Int = 0

    var ackSequence: Int = 0
    var ackStatus: Int = 0
    var ackCommandId: Int = 0

    var commandKind: Int = 0
    var commandId: Int = 0
    var commandPage: Int = 0
    var commandX: Int = 0
    var commandY: Int = 0
    var commandEventTimeMs: Int = 0
}

class MotoMapPointInput {
    var latitudeE6: Int = 0
    var longitudeE6: Int = 0

    constructor()

    constructor(latitudeE6: Int, longitudeE6: Int) {
        this.latitudeE6 = latitudeE6
        this.longitudeE6 = longitudeE6
    }
}

class MotoMapRoadInput {
    var roadClass: Int = MotoMapRoadClass.OTHER
    var points: Array<MotoMapPointInput> = emptyArray()
}

class MotoMapBuildingInput {
    var buildingClass: Int = MotoMapBuildingClass.GENERIC
    var points: Array<MotoMapPointInput> = emptyArray()
}

class MotoMapSceneInput {
    var sceneRevision: Int = 0
    var originLatitudeE6: Int = 0
    var originLongitudeE6: Int = 0
    var radiusM: Int = 0
    var roads: Array<MotoMapRoadInput> = emptyArray()
    var buildings: Array<MotoMapBuildingInput> = emptyArray()
}

/** `moto::ble::MapRoadClass` codes. */
object MotoMapRoadClass {
    const val MOTORWAY = 0
    const val PRIMARY = 1
    const val SECONDARY = 2
    const val RESIDENTIAL = 3
    const val SERVICE = 4
    const val OTHER = 5
}

/** `moto::ble::MapBuildingClass` codes. */
object MotoMapBuildingClass {
    const val GENERIC = 0
    const val LANDMARK = 1
    const val PARKING = 2
}

/** A decoded device-to-phone message. */
sealed interface MotoInboundMessage {
    val sequence: Int
    val ackRequested: Boolean
    val duplicate: Boolean
}

data class MotoConnectionStatus(
    override val sequence: Int,
    override val ackRequested: Boolean,
    override val duplicate: Boolean,
    val role: Int,
    val state: Int,
    val minimumVersion: Int,
    val maximumVersion: Int,
    val capabilities: Int,
    val sessionId: Int,
    val maxFrameSize: Int,
    val heartbeatIntervalMs: Int,
) : MotoInboundMessage

data class MotoHeartbeat(
    override val sequence: Int,
    override val ackRequested: Boolean,
    override val duplicate: Boolean,
    val sessionId: Int,
    val monotonicMs: Int,
    val statusFlags: Int,
) : MotoInboundMessage

data class MotoAck(
    override val sequence: Int,
    override val ackRequested: Boolean,
    override val duplicate: Boolean,
    val acknowledgedSequence: Int,
    val status: Int,
    val commandId: Int,
) : MotoInboundMessage

data class MotoDeviceCommand(
    override val sequence: Int,
    override val ackRequested: Boolean,
    override val duplicate: Boolean,
    val kind: Int,
    val commandId: Int,
    val page: Int,
    val x: Int,
    val y: Int,
    val eventTimeMs: Int,
) : MotoInboundMessage {
    fun kindOrNull(): DeviceCommandKind? =
        DeviceCommandKind.entries.firstOrNull { it.code == kind }
}

data class MotoUnhandledMessage(
    override val sequence: Int,
    override val ackRequested: Boolean,
    override val duplicate: Boolean,
    val messageType: Int,
) : MotoInboundMessage

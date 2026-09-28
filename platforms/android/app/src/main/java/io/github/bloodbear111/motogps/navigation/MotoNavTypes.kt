package io.github.bloodbear111.motogps.navigation

import io.github.bloodbear111.motogps.protocol.ManeuverType
import io.github.bloodbear111.motogps.protocol.NavigationState
import io.github.bloodbear111.motogps.protocol.NetworkState
import io.github.bloodbear111.motogps.protocol.TrafficLevel

/**
 * Effect returned by the shared `NavCore`.
 *
 * The core never performs I/O: it answers every event with the commands the
 * platform adapter must execute. The constructor signature is read by JNI, so
 * its parameter order is part of the ABI (`moto_jni.cpp` `CommandsToArray`).
 */
class MotoNavCommand(
    val typeName: String,
    val requestId: Int,
    val originLongitudeDeg: Double,
    val originLatitudeDeg: Double,
    val destinationLongitudeDeg: Double,
    val destinationLatitudeDeg: Double,
    val reroute: Boolean,
    val routeId: String,
) {
    val isRouteRequest: Boolean get() = typeName == TYPE_REQUEST_ROUTE
    val isTrafficRequest: Boolean get() = typeName == TYPE_REQUEST_TRAFFIC

    companion object {
        const val TYPE_REQUEST_ROUTE = "request_route"
        const val TYPE_REQUEST_TRAFFIC = "request_traffic"
    }
}

/**
 * JNI output buffer for `moto::nav::NavSnapshot`.
 *
 * Field names and types are part of the Kotlin/C++ ABI; `proguard-rules.pro`
 * keeps this class from being renamed. Route view arrays are pre-sized to
 * `nav::kRouteViewPointCapacity` (24) and filled in place.
 */
class MotoNavSnapshotBuffer {
    companion object {
        /** `moto::nav::kRouteViewPointCapacity`. */
        const val ROUTE_VIEW_CAPACITY = 24
    }

    var state: Int = NavigationState.Idle.code
    var network: Int = NetworkState.Offline.code
    var displayPage: Int = 0

    var hasDestination: Boolean = false
    var hasUsableFix: Boolean = false
    var gnssStale: Boolean = true
    var offRoute: Boolean = false
    var routeRequestInFlight: Boolean = false
    var trafficRequestInFlight: Boolean = false

    var positionLongitudeDeg: Double = 0.0
    var positionLatitudeDeg: Double = 0.0
    var destinationLongitudeDeg: Double = 0.0
    var destinationLatitudeDeg: Double = 0.0
    var speedMps: Double = 0.0
    var headingDeg: Double = 0.0
    var horizontalAccuracyM: Double = 0.0
    var crossTrackDistanceM: Double = 0.0
    var speedLimitKph: Int = 0

    var hasRouteView: Boolean = false
    var routeViewOriginLongitudeDeg: Double = 0.0
    var routeViewOriginLatitudeDeg: Double = 0.0
    var routeViewLatitudes: DoubleArray = DoubleArray(ROUTE_VIEW_CAPACITY)
    var routeViewLongitudes: DoubleArray = DoubleArray(ROUTE_VIEW_CAPACITY)
    var routeViewPointCount: Int = 0

    var routeId: String = ""
    var routeProgressM: Double = 0.0
    var totalDistanceM: Double = 0.0
    var remainingDistanceM: Double = 0.0
    var remainingDurationS: Long = 0

    var hasNextManeuver: Boolean = false
    var maneuverId: Int = 0
    var maneuverType: Int = ManeuverType.Unknown.code
    var distanceToManeuverM: Double = 0.0
    var roadName: String = ""
    var instruction: String = ""
    var roundaboutExit: Int = 0
    var trafficAhead: Int = TrafficLevel.Unknown.code

    var nowMs: Long = 0
    var lastFixMs: Long = 0
    var lastTrafficUpdateMs: Long = 0
    var routeGeneration: Int = 0

    fun maneuverTypeOrNull(): ManeuverType? =
        ManeuverType.entries.firstOrNull { it.code == maneuverType }

    fun navigationStateOrNull(): NavigationState? =
        NavigationState.entries.firstOrNull { it.code == state }

    fun networkStateOrNull(): NetworkState? =
        NetworkState.entries.firstOrNull { it.code == network }

    fun trafficLevelOrNull(): TrafficLevel? =
        TrafficLevel.entries.firstOrNull { it.code == trafficAhead }

    /** Route view points actually present, in (latitude, longitude) pairs. */
    fun routeViewPoints(): List<Pair<Double, Double>> {
        val count = routeViewPointCount.coerceIn(0, ROUTE_VIEW_CAPACITY)
        return (0 until count).map { index ->
            routeViewLatitudes[index] to routeViewLongitudes[index]
        }
    }
}

/** A projected maneuver list entry, as accepted by `RouteReady`. */
data class MotoRouteManeuver(
    val id: Int,
    val type: ManeuverType,
    val routeOffsetM: Double,
    val roadName: String,
    val instruction: String,
    val roundaboutExit: Int,
)

/** A projected traffic segment, as accepted by `RouteReady`/`TrafficUpdated`. */
data class MotoTrafficSegment(
    val startOffsetM: Double,
    val endOffsetM: Double,
    val level: TrafficLevel,
)

/** Provider-neutral route, already converted to GCJ-02 by the gateway. */
data class MotoRoutePlan(
    val routeId: String,
    val latitudesDeg: DoubleArray,
    val longitudesDeg: DoubleArray,
    val maneuvers: List<MotoRouteManeuver>,
    val traffic: List<MotoTrafficSegment>,
    val totalDistanceM: Double,
    val totalDurationS: Int,
    val speedLimitKph: Int = 0,
    val generatedAtMs: Long = 0,
) {
    fun pointCount(): Int = minOf(latitudesDeg.size, longitudesDeg.size)
}

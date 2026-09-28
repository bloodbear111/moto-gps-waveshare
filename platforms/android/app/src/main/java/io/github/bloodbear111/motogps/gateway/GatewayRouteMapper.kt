package io.github.bloodbear111.motogps.gateway

import io.github.bloodbear111.motogps.navigation.MotoRouteManeuver
import io.github.bloodbear111.motogps.navigation.MotoRoutePlan
import io.github.bloodbear111.motogps.navigation.MotoTrafficSegment
import io.github.bloodbear111.motogps.protocol.ManeuverType
import io.github.bloodbear111.motogps.protocol.TrafficLevel
import kotlin.math.roundToLong

/**
 * Projects a gateway `RouteBundle` onto the shared navigation core's route type.
 *
 * Two deliberate non-actions:
 * * `polyline` is copied as-is. It is GCJ-02 by contract, and the shared core's
 *   `RouteBundle::polyline` is also GCJ-02, so converting here would shift the
 *   route twice.
 * * `speedLimitKph` stays 0. Neither the route schema nor AMap Route Planning v2
 *   exposes a trustworthy legal speed limit, and the round display hides the
 *   field when it is zero. Inventing a number would be worse than "unknown".
 */
object GatewayRouteMapper {

    /** Maneuver strings from `route-bundle.v1.schema.json`. */
    private val maneuverTypes: Map<String, ManeuverType> = mapOf(
        "unknown" to ManeuverType.Unknown,
        "continue" to ManeuverType.Continue,
        "slight_left" to ManeuverType.SlightLeft,
        "left" to ManeuverType.Left,
        "sharp_left" to ManeuverType.SharpLeft,
        "u_turn_left" to ManeuverType.UTurnLeft,
        "slight_right" to ManeuverType.SlightRight,
        "right" to ManeuverType.Right,
        "sharp_right" to ManeuverType.SharpRight,
        "u_turn_right" to ManeuverType.UTurnRight,
        "roundabout" to ManeuverType.Roundabout,
        "exit" to ManeuverType.Exit,
        "arrive" to ManeuverType.Arrive,
    )

    /** Traffic strings from `route-bundle.v1.schema.json`. */
    private val trafficLevels: Map<String, TrafficLevel> = mapOf(
        "unknown" to TrafficLevel.Unknown,
        "free_flow" to TrafficLevel.FreeFlow,
        "slow" to TrafficLevel.Slow,
        "congested" to TrafficLevel.Congested,
        "severe" to TrafficLevel.Severe,
    )

    /**
     * An unrecognised enum string becomes `Unknown` rather than a guessed
     * direction or congestion level. The protocol expresses "no data" as
     * `unknown`, so the display degrades instead of lying.
     */
    fun maneuverType(wireName: String): ManeuverType =
        maneuverTypes[wireName.lowercase()] ?: ManeuverType.Unknown

    fun trafficLevel(wireName: String): TrafficLevel =
        trafficLevels[wireName.lowercase()] ?: TrafficLevel.Unknown

    fun toRoutePlan(bundle: RouteBundleDto): MotoRoutePlan {
        val latitudes = DoubleArray(bundle.polyline.size) {
            bundle.polyline[it].latitudeDeg
        }
        val longitudes = DoubleArray(bundle.polyline.size) {
            bundle.polyline[it].longitudeDeg
        }

        val maneuvers = bundle.maneuvers.map { maneuver ->
            MotoRouteManeuver(
                id = maneuver.id,
                type = maneuverType(maneuver.type),
                routeOffsetM = maneuver.routeOffsetM,
                roadName = maneuver.roadName,
                instruction = maneuver.instruction,
                roundaboutExit = maneuver.roundaboutExit.coerceIn(0, 32),
            )
        }

        val traffic = bundle.traffic.mapNotNull { segment ->
            // The core requires an increasing, non-empty span; the transformer
            // already drops empty ones, but a malformed bundle must not create a
            // backwards segment in the rider's ETA.
            if (segment.endOffsetM <= segment.startOffsetM) {
                null
            } else {
                MotoTrafficSegment(
                    startOffsetM = segment.startOffsetM,
                    endOffsetM = segment.endOffsetM,
                    level = trafficLevel(segment.level),
                )
            }
        }

        return MotoRoutePlan(
            routeId = bundle.routeId,
            latitudesDeg = latitudes,
            longitudesDeg = longitudes,
            maneuvers = maneuvers,
            traffic = traffic,
            totalDistanceM = bundle.totalDistanceM,
            totalDurationS = bundle.totalDurationS.coerceAtLeast(0),
            // Never fabricated; see the class comment.
            speedLimitKph = 0,
            generatedAtMs = bundle.generatedAtMs,
        )
    }

    /**
     * Scales a full-route duration to the rider's remaining fraction, matching
     * `TrafficRefreshPolicy.remainingDurationSeconds` on the iOS side: the
     * gateway reports duration for the whole route, while the shared core wants
     * an ETA from the current progress.
     */
    fun remainingDurationSeconds(
        refreshedCompleteDurationS: Int,
        remainingDistanceM: Double,
        completeDistanceM: Double,
    ): Int {
        if (refreshedCompleteDurationS <= 0 ||
            !remainingDistanceM.isFinite() ||
            !completeDistanceM.isFinite() ||
            completeDistanceM <= 0.0
        ) {
            return 0
        }
        val fraction = (remainingDistanceM / completeDistanceM).coerceIn(0.0, 1.0)
        return (refreshedCompleteDurationS.toDouble() * fraction)
            .roundToLong()
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    /**
     * Traffic offsets are measured from the start of the complete route, so a
     * refreshed response may only replace the current route's traffic when the
     * geometry and length still match. Mirrors
     * `TrafficRefreshPolicy.sharesCompleteRouteBaseline`.
     */
    fun sharesCompleteRouteBaseline(
        current: RouteBundleDto,
        refreshed: RouteBundleDto,
    ): Boolean {
        if (current.coordinateSystem != "GCJ-02" ||
            refreshed.coordinateSystem != current.coordinateSystem
        ) {
            return false
        }
        if (current.polyline.size != refreshed.polyline.size) return false
        for (index in current.polyline.indices) {
            val left = current.polyline[index]
            val right = refreshed.polyline[index]
            if (left.latitudeDeg != right.latitudeDeg ||
                left.longitudeDeg != right.longitudeDeg
            ) {
                return false
            }
        }
        val toleranceM = maxOf(5.0, current.totalDistanceM * 0.002)
        return kotlin.math.abs(refreshed.totalDistanceM - current.totalDistanceM) <= toleranceM
    }
}

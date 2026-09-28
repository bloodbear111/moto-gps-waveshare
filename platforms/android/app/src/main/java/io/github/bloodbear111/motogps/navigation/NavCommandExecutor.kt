package io.github.bloodbear111.motogps.navigation

import io.github.bloodbear111.motogps.gateway.GatewayFailure
import io.github.bloodbear111.motogps.gateway.GatewayResult
import io.github.bloodbear111.motogps.gateway.GatewayRoute
import io.github.bloodbear111.motogps.gateway.GatewayRouteMapper
import io.github.bloodbear111.motogps.gateway.MotoGatewayClient
import io.github.bloodbear111.motogps.gateway.RouteBundleDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The route the rider is currently following, with the endpoints it was planned
 * from.
 *
 * The endpoints are kept because a traffic refresh must re-request **this**
 * route, not a route from wherever the rider happens to be now. Re-planning from
 * the current position returns different geometry, and since traffic offsets are
 * measured from the start of the complete route they would then land on the
 * wrong road. This mirrors `ActiveRoute` in the iOS runtime.
 */
data class ActiveRouteContext(
    val routeId: String,
    val bundle: RouteBundleDto,
    val originWgs84: Wgs84Point,
    val destinationWgs84: Wgs84Point,
    val destinationPoiId: String? = null,
)

/**
 * What to feed back into the shared core after running a command.
 *
 * The executor returns values instead of mutating `NavCore` itself: that keeps
 * this class testable with a fake gateway and keeps "talk to the network"
 * separate from "change navigation state".
 */
sealed interface NavCommandOutcome {

    /** A fresh route for [requestId]. [plan] is in provider GCJ-02. */
    data class RouteLoaded(
        val requestId: Int,
        val route: GatewayRoute,
        val plan: MotoRoutePlan,
    ) : NavCommandOutcome

    /** Planning failed; the core decides whether to retry. */
    data class RouteFailed(
        val requestId: Int,
        val retryable: Boolean,
        val failure: GatewayFailure,
    ) : NavCommandOutcome

    /**
     * Traffic was refreshed for a route that still shares the current baseline,
     * so the offsets are valid.
     *
     * [completeRouteDurationS] is the duration for the **whole** route, exactly
     * as the gateway reports it. The caller must scale it to the rider's
     * remaining distance before handing it to `NavCore`; see
     * [GatewayRouteMapper.remainingDurationSeconds].
     */
    data class TrafficLoaded(
        val requestId: Int,
        val routeId: String,
        val segments: List<MotoTrafficSegment>,
        val completeRouteDurationS: Int,
        val observedAtMs: Long,
    ) : NavCommandOutcome

    data class TrafficFailed(
        val requestId: Int,
        val failure: GatewayFailure,
    ) : NavCommandOutcome

    /** A command this executor does not handle; the core should not wait on it. */
    data class Ignored(val requestId: Int, val reason: String) : NavCommandOutcome
}

/**
 * Runs the effects the shared `NavCore` asks for.
 *
 * `NavCore` performs no I/O: it emits `NavCommand`s and waits for `RouteReady`,
 * `RouteFailed`, `TrafficUpdated` or `TrafficUpdateFailed`. This class is the
 * Android half of that contract, and it is where "refuse to guess" lives:
 *
 * * A request that cannot reach the gateway becomes a failure carrying the
 *   gateway's own `retryable` flag, so the core's retry schedule decides what
 *   happens instead of a hidden fallback route.
 * * A traffic refresh is only accepted when the returned bundle still matches
 *   the active route's geometry, id and length.
 */
class NavCommandExecutor(
    private val gateway: MotoGatewayClient,
    private val gatewayBaseUrl: () -> String?,
    private val clock: () -> Long,
) {

    /** Executes every command in order, emitting one outcome per command. */
    fun outcomes(
        commands: List<MotoNavCommand>,
        activeRoute: ActiveRouteContext?,
    ): Flow<NavCommandOutcome> = flow {
        for (command in commands) {
            emit(execute(command, activeRoute))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun execute(
        command: MotoNavCommand,
        activeRoute: ActiveRouteContext?,
    ): NavCommandOutcome = when {
        command.isRouteRequest -> runRouteRequest(command, activeRoute)
        command.isTrafficRequest -> runTrafficRequest(command, activeRoute)
        else -> NavCommandOutcome.Ignored(
            command.requestId,
            "unsupported command '${command.typeName}'",
        )
    }

    private suspend fun runRouteRequest(
        command: MotoNavCommand,
        activeRoute: ActiveRouteContext?,
    ): NavCommandOutcome {
        // A route request carries its own WGS84 endpoints from the core.
        val origin = Wgs84Point(
            latitudeDeg = command.originLatitudeDeg,
            longitudeDeg = command.originLongitudeDeg,
        )
        val destination = Wgs84Point(
            latitudeDeg = command.destinationLatitudeDeg,
            longitudeDeg = command.destinationLongitudeDeg,
        )
        val result = gateway.requestRoute(
            baseUrl = gatewayBaseUrl(),
            requestId = command.requestId,
            originWgs84 = origin,
            destinationWgs84 = destination,
            isReroute = command.reroute,
            // Matches the iOS runtime: report the route currently being
            // navigated, if any, for observability on the gateway side.
            previousRouteId = activeRoute?.routeId,
            destinationPoiId = activeRoute?.destinationPoiId,
        )
        return when (result) {
            is GatewayResult.Success -> NavCommandOutcome.RouteLoaded(
                requestId = command.requestId,
                route = result.value,
                plan = GatewayRouteMapper.toRoutePlan(result.value.bundle),
            )

            is GatewayResult.Failure -> NavCommandOutcome.RouteFailed(
                requestId = command.requestId,
                retryable = result.failure.isRetryable(),
                failure = result.failure,
            )
        }
    }

    private suspend fun runTrafficRequest(
        command: MotoNavCommand,
        activeRoute: ActiveRouteContext?,
    ): NavCommandOutcome {
        // The core's traffic command carries no endpoints, and refreshing from
        // the rider's current position would return different geometry. Reuse
        // the endpoints the active route was planned with.
        if (activeRoute == null || activeRoute.routeId != command.routeId) {
            return NavCommandOutcome.TrafficFailed(
                requestId = command.requestId,
                failure = GatewayFailure.Protocol(
                    "traffic refresh for '${command.routeId}' does not match the " +
                        "active route '${activeRoute?.routeId ?: "none"}'",
                ),
            )
        }

        val result = gateway.requestRoute(
            baseUrl = gatewayBaseUrl(),
            requestId = command.requestId,
            originWgs84 = activeRoute.originWgs84,
            destinationWgs84 = activeRoute.destinationWgs84,
            isReroute = false,
            previousRouteId = activeRoute.routeId,
            destinationPoiId = activeRoute.destinationPoiId,
        )
        val route = (result as? GatewayResult.Success)?.value
            ?: return NavCommandOutcome.TrafficFailed(
                requestId = command.requestId,
                failure = (result as GatewayResult.Failure).failure,
            )

        if (route.bundle.routeId != activeRoute.routeId) {
            return NavCommandOutcome.TrafficFailed(
                requestId = command.requestId,
                failure = GatewayFailure.Protocol(
                    "traffic refresh returned route '${route.bundle.routeId}', " +
                        "active route is '${activeRoute.routeId}'",
                ),
            )
        }
        // AMap may pick a different route during a refresh. Its traffic offsets
        // are unsafe for the displayed route unless the complete GCJ-02 polyline
        // still matches exactly.
        if (!GatewayRouteMapper.sharesCompleteRouteBaseline(activeRoute.bundle, route.bundle)) {
            return NavCommandOutcome.TrafficFailed(
                requestId = command.requestId,
                failure = GatewayFailure.Protocol(
                    "traffic refresh geometry no longer matches the active route; " +
                        "offsets would land on the wrong road",
                ),
            )
        }

        return NavCommandOutcome.TrafficLoaded(
            requestId = command.requestId,
            routeId = activeRoute.routeId,
            segments = route.bundle.traffic.mapNotNull { segment ->
                if (segment.endOffsetM <= segment.startOffsetM) {
                    null
                } else {
                    MotoTrafficSegment(
                        startOffsetM = segment.startOffsetM,
                        endOffsetM = segment.endOffsetM,
                        level = GatewayRouteMapper.trafficLevel(segment.level),
                    )
                }
            },
            completeRouteDurationS = route.bundle.totalDurationS,
            observedAtMs = clock(),
        )
    }
}

/**
 * Whether the core should retry. A misconfiguration or a schema violation will
 * not fix itself, so treating it as retryable would only burn gateway quota.
 */
internal fun GatewayFailure.isRetryable(): Boolean = when (this) {
    is GatewayFailure.Service -> retryable
    is GatewayFailure.Transport -> true
    GatewayFailure.NotConfigured -> false
    is GatewayFailure.Protocol -> false
    is GatewayFailure.Stale -> false
}

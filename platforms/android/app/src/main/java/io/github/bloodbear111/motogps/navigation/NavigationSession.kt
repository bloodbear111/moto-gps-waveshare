package io.github.bloodbear111.motogps.navigation

import io.github.bloodbear111.motogps.ble.MotoBleCentral
import io.github.bloodbear111.motogps.gateway.GatewayRouteMapper
import io.github.bloodbear111.motogps.protocol.MotoSnapshotInput
import io.github.bloodbear111.motogps.protocol.NavigationState
import io.github.bloodbear111.motogps.protocol.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Glue between the shared `NavCore`, the gateway, the platform location source
 * and the BLE display.
 *
 * `NavCore` owns the navigation itself - route matching, maneuver projection,
 * off-route detection, reroute and traffic scheduling - and never performs I/O:
 * it answers every event with the [MotoNavCommand]s the platform must execute.
 * This class is the Android half of that contract and nothing more. It does not
 * invent maneuver text, distances or a fallback route; everything shown on the
 * phone and sent to the display comes out of the core's snapshot.
 */
class NavigationSession(
    private val core: MotoNavCore,
    private val executor: NavCommandExecutor,
    private val location: NavigationSource,
    private val display: MotoBleCentral,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    /** What the UI renders. Mutable only inside this class. */
    data class Snapshot(
        val active: Boolean = false,
        val destinationName: String? = null,
        val state: NavigationState = NavigationState.Idle,
        val speedKph: Int = 0,
        val remainingDistanceM: Double = 0.0,
        val remainingDurationS: Long = 0,
        val nextInstruction: String = "",
        val distanceToManeuverM: Double = 0.0,
        val offRoute: Boolean = false,
        val gnssStale: Boolean = true,
        val hasFix: Boolean = false,
        val horizontalAccuracyM: Double = 0.0,
        val sentSnapshots: Int = 0,
        val sentRouteWindows: Int = 0,
        /**
         * What the core believes about the gateway, and whether it has a route
         * request outstanding. The core silently builds no route while it thinks
         * the network is offline, so this has to be visible.
         */
        val gatewayOnline: Boolean = false,
        val routeRequestInFlight: Boolean = false,
        /** Fixes handed to the core. Zero means the phone never produced one. */
        val fixesAccepted: Int = 0,
        /** Age of the newest fix in ms, or null when there has never been one. */
        val lastFixAgeMs: Long? = null,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private var activeRoute: ActiveRouteContext? = null
    private var destinationName: String? = null
    private var fixJob: Job? = null
    private var tickJob: Job? = null
    private var fixesAccepted = 0
    private var lastFixAtMs: Long? = null

    /**
     * Begins navigation towards [destination]. The core answers with the first
     * route request; the tick and fix loops keep feeding it afterwards.
     */
    fun start(destination: Wgs84Point, label: String?) {
        stop()
        destinationName = label
        activeRoute = null
        _state.value = Snapshot(active = true, destinationName = label)
        // The core only builds a route request while it believes the phone can
        // reach the gateway; its default is Offline. The iOS runtime states
        // "online" here for exactly that reason, and this session used to leave
        // it Offline (its Bluetooth-linked setter was never called from
        // anywhere), so `request_route_if_possible` returned on every tick and
        // no route was ever requested - the round display sat on "choose a
        // destination on the phone" with a perfect fix and a working gateway.
        //
        // This is a statement about the phone's data path, not about the round
        // display: a display that is not connected must not stop the phone from
        // planning a route.
        drain(core.setNetworkState(NetworkState.Online))
        drain(core.beginNavigation(destination))
        startFixLoop()
        startTickLoop()
    }

    fun stop() {
        fixJob?.cancel()
        tickJob?.cancel()
        fixJob = null
        tickJob = null
        if (_state.value.active) {
            drain(core.cancelNavigation())
        }
        activeRoute = null
        destinationName = null
        fixesAccepted = 0
        lastFixAtMs = null
        // One final Idle snapshot so the display leaves navigation instead of
        // holding the last frame forever.
        mirrorToDisplay()
        _state.value = Snapshot()
    }

    /**
     * Drops the location subscription and subscribes again.
     *
     * An OEM location client can accept a request and then never deliver
     * anything; re-registering is the recovery the rider can trigger without
     * rebooting the phone. The fix counter is deliberately *not* reset: it is
     * the evidence of whether the retry worked.
     */
    fun restartLocation() {
        if (!_state.value.active) return
        startFixLoop()
    }

    private fun startFixLoop() {
        fixJob?.cancel()
        fixJob = scope.launch {
            try {
                location.fixes().collect { fix ->
                    fixesAccepted += 1
                    lastFixAtMs = clock()
                    drain(core.pushFix(fix))
                }
            } catch (cancelled: CancellationException) {
                // Restarting the location client cancels the previous loop on
                // purpose. Reporting that as "StandaloneCoroutine was cancelled"
                // made a deliberate restart look like a fault.
                throw cancelled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(lastError = error.message ?: "location failed")
            }
        }
    }

    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                if (!_state.value.active) break
                drain(core.tick(clock()))
            }
        }
    }

    /**
     * Runs everything the core asked for and feeds the answers back in. The
     * core's own retry schedule decides what happens after a failure, so a
     * gateway error is reported as-is rather than replaced by anything.
     */
    private fun drain(commands: List<MotoNavCommand>) {
        if (commands.isEmpty()) {
            mirrorToDisplay()
            return
        }
        scope.launch {
            var failure: String? = null
            executor.outcomes(commands, activeRoute).collect { outcome ->
                when (outcome) {
                    is NavCommandOutcome.RouteLoaded -> {
                        activeRoute = ActiveRouteContext(
                            routeId = outcome.route.bundle.routeId,
                            bundle = outcome.route.bundle,
                            originWgs84 = Wgs84Point(
                                longitudeDeg = outcome.route.bundle.polyline.firstOrNull()
                                    ?.longitudeDeg ?: 0.0,
                                latitudeDeg = outcome.route.bundle.polyline.firstOrNull()
                                    ?.latitudeDeg ?: 0.0,
                            ),
                            destinationWgs84 = Wgs84Point(
                                longitudeDeg = outcome.route.bundle.polyline.lastOrNull()
                                    ?.longitudeDeg ?: 0.0,
                                latitudeDeg = outcome.route.bundle.polyline.lastOrNull()
                                    ?.latitudeDeg ?: 0.0,
                            ),
                        )
                        drain(core.acceptRoute(outcome.plan, outcome.requestId, clock()))
                    }

                    is NavCommandOutcome.RouteFailed -> {
                        failure = outcome.failure.message ?: "route planning failed"
                        drain(
                            core.rejectRoute(
                                outcome.requestId,
                                outcome.retryable,
                                clock(),
                            ),
                        )
                    }

                    is NavCommandOutcome.TrafficLoaded -> {
                        // The gateway reports the duration of the whole route,
                        // while the core wants an ETA from where the rider
                        // actually is. Scale by the remaining fraction using the
                        // core's own progress, never a fresh plan.
                        val core0 = core.snapshot()
                        val remaining = GatewayRouteMapper.remainingDurationSeconds(
                            refreshedCompleteDurationS = outcome.completeRouteDurationS,
                            remainingDistanceM = core0.remainingDistanceM,
                            completeDistanceM = core0.totalDistanceM,
                        )
                        drain(
                            core.acceptTraffic(
                                outcome.routeId,
                                outcome.segments,
                                remaining,
                                outcome.requestId,
                                outcome.observedAtMs,
                            ),
                        )
                    }

                    is NavCommandOutcome.TrafficFailed -> {
                        failure = outcome.failure.message ?: "traffic refresh failed"
                        drain(core.rejectTraffic(outcome.requestId, clock()))
                    }

                    is NavCommandOutcome.Ignored -> Unit
                }
            }
            if (failure != null) {
                _state.value = _state.value.copy(lastError = failure)
            }
            mirrorToDisplay()
        }
    }

    /**
     * Copies the core's snapshot to the display. This is the only path that
     * writes navigation state to the round screen, so the phone and the watch
     * can never disagree about what is being navigated.
     */
    private fun mirrorToDisplay() {
        val snapshot = core.snapshot()
        val input = MotoSnapshotInput().apply {
            state = snapshot.state
            network = snapshot.network
            displayPage = snapshot.displayPage
            maneuver = snapshot.maneuverType
            traffic = snapshot.trafficAhead
            hasDestination = snapshot.hasDestination
            hasFix = snapshot.hasUsableFix
            gnssStale = snapshot.gnssStale
            offRoute = snapshot.offRoute
            hasNextManeuver = snapshot.hasNextManeuver
            routeRequestInFlight = snapshot.routeRequestInFlight
            trafficRequestInFlight = snapshot.trafficRequestInFlight
            hasRouteView = snapshot.hasRouteView
            routeGeneration = snapshot.routeGeneration
            maneuverId = snapshot.maneuverId
            distanceToManeuverM = snapshot.distanceToManeuverM.toInt()
            remainingDistanceM = snapshot.remainingDistanceM.toInt()
            remainingDurationS = snapshot.remainingDurationS.toInt()
            routeProgressM = snapshot.routeProgressM.toInt()
            totalDistanceM = snapshot.totalDistanceM.toInt()
            speedDeciKph = (snapshot.speedMps * 36.0).toInt()
            speedLimitKph = snapshot.speedLimitKph
            headingCdeg = (snapshot.headingDeg * 100.0).toInt()
            accuracyDm = (snapshot.horizontalAccuracyM * 10.0).toInt()
            crossTrackDm = (snapshot.crossTrackDistanceM * 10.0).toInt()
            roundaboutExit = snapshot.roundaboutExit
            roadNameUtf8 = snapshot.roadName.toByteArray(Charsets.UTF_8)
            instructionUtf8 = snapshot.instruction.toByteArray(Charsets.UTF_8)
        }
        val sent = display.sendNavigationSnapshot(input)
        var windows = _state.value.sentRouteWindows

        if (snapshot.hasRouteView && snapshot.routeViewPointCount > 0) {
            val count = snapshot.routeViewPointCount.coerceAtMost(
                MotoNavSnapshotBuffer.ROUTE_VIEW_CAPACITY,
            )
            val latitudes = IntArray(count) {
                (snapshot.routeViewLatitudes[it] * E6).toInt()
            }
            val longitudes = IntArray(count) {
                (snapshot.routeViewLongitudes[it] * E6).toInt()
            }
            val originLatitude = (snapshot.routeViewOriginLatitudeDeg * E6).toInt()
            val originLongitude = (snapshot.routeViewOriginLongitudeDeg * E6).toInt()
            if (display.sendRouteGeometry(
                    snapshot.routeId,
                    snapshot.routeGeneration,
                    originLatitude,
                    originLongitude,
                    latitudes,
                    longitudes,
                    count,
                )
            ) {
                windows += 1
            }
        }

        _state.value = _state.value.copy(
            destinationName = destinationName,
            state = snapshot.navigationStateOrNull() ?: NavigationState.Idle,
            speedKph = (snapshot.speedMps * 3.6).toInt(),
            remainingDistanceM = snapshot.remainingDistanceM,
            remainingDurationS = snapshot.remainingDurationS,
            nextInstruction = snapshot.instruction,
            distanceToManeuverM = snapshot.distanceToManeuverM,
            offRoute = snapshot.offRoute,
            gnssStale = snapshot.gnssStale,
            hasFix = snapshot.hasUsableFix,
            horizontalAccuracyM = snapshot.horizontalAccuracyM,
            sentSnapshots = if (sent) _state.value.sentSnapshots + 1 else _state.value.sentSnapshots,
            sentRouteWindows = windows,
            gatewayOnline = snapshot.network == NetworkState.Online.code,
            routeRequestInFlight = snapshot.routeRequestInFlight,
            fixesAccepted = fixesAccepted,
            lastFixAgeMs = lastFixAtMs?.let { clock() - it },
        )
    }

    private companion object {
        const val TICK_INTERVAL_MS = 1_000L
        const val E6 = 1_000_000.0
    }
}

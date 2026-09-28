package io.github.bloodbear111.motogps.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bloodbear111.motogps.protocol.DisplayPage
import io.github.bloodbear111.motogps.protocol.ManeuverType
import io.github.bloodbear111.motogps.protocol.NavigationState
import io.github.bloodbear111.motogps.protocol.NetworkState
import io.github.bloodbear111.motogps.protocol.TrafficLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JNI lifetime and behaviour checks for the shared `NavApp`.
 *
 * Expected values come from reading `shared/nav_core/nav_core.cpp`, not from
 * guessing: a destination alone does **not** emit a route request; the request is
 * produced once a usable fix arrives and the state moves to `Planning`
 * (`NavCore::accept_fix` -> `enter_planning` -> `request_route_if_possible`).
 *
 * The route below is synthetic geometry near Jinan, never a real track.
 */
@RunWith(AndroidJUnit4::class)
class MotoNavCoreInstrumentedTest {

    // WGS84 positions pushed as fixes.
    private val fixLat = doubleArrayOf(36.675100, 36.675600, 36.676100)
    private val fixLon = doubleArrayOf(117.127950, 117.129050, 117.130400)

    // Provider (GCJ-02) route geometry spanning the same area.
    private val routeLat = doubleArrayOf(
        36.674900, 36.675300, 36.675700, 36.676100, 36.676500,
    )
    private val routeLon = doubleArrayOf(
        117.127500, 117.128200, 117.129000, 117.130000, 117.131000,
    )

    private fun route() = MotoRoutePlan(
        routeId = "android-instrumented-route",
        latitudesDeg = routeLat,
        longitudesDeg = routeLon,
        maneuvers = listOf(
            MotoRouteManeuver(
                id = 1,
                type = ManeuverType.Right,
                routeOffsetM = 300.0,
                roadName = "东长安街",
                instruction = "前方路口右转",
                roundaboutExit = 0,
            ),
        ),
        traffic = listOf(
            MotoTrafficSegment(0.0, 400.0, TrafficLevel.FreeFlow),
            MotoTrafficSegment(400.0, 1_000.0, TrafficLevel.Slow),
        ),
        totalDistanceM = 1_000.0,
        totalDurationS = 180,
        speedLimitKph = 0,
        generatedAtMs = 1_700_000_000_000,
    )

    private fun goodFix(index: Int, timestampMs: Long) = MotoGnssFix(
        latitudeDeg = fixLat[index],
        longitudeDeg = fixLon[index],
        accuracyM = 5.0,
        speedMps = 8.0,
        headingDeg = 45.0,
        timestampMs = timestampMs,
    )

    @Test
    fun repeatedCreateAndCloseDoesNotLeakOrCrash() {
        repeat(25) {
            MotoNavCore().use { core -> core.snapshot() }
        }
        // Still able to allocate and use a fresh instance afterwards.
        MotoNavCore().use { core ->
            assertEquals(NavigationState.Idle, core.snapshot().navigationStateOrNull())
        }
    }

    @Test
    fun destinationAloneOnlyAcquiresItDoesNotRequestARoute() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Online)
            val commands = core.beginNavigation(
                destination = Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            assertTrue(
                "beginNavigation must not fabricate a route request before a fix",
                commands.none { it.isRouteRequest },
            )
            val snapshot = core.snapshot()
            assertEquals(NavigationState.Acquiring, snapshot.navigationStateOrNull())
            assertTrue(snapshot.hasDestination)
            assertEquals(36.6761, snapshot.destinationLatitudeDeg, 1e-9)
            assertEquals(117.1304, snapshot.destinationLongitudeDeg, 1e-9)
            assertFalse(snapshot.hasUsableFix)
        }
    }

    @Test
    fun theFirstUsableFixRequestsARouteWithRawWgs84Coordinates() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Online)
            core.beginNavigation(
                Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            val commands = core.pushFix(goodFix(index = 0, timestampMs = 1_000))
            val request = commands.firstOrNull { it.isRouteRequest }
            assertNotNull("expected a route request after the first usable fix", request)
            // The gateway contract takes WGS84; the core must not pre-convert.
            assertEquals(fixLat[0], request!!.originLatitudeDeg, 1e-9)
            assertEquals(fixLon[0], request.originLongitudeDeg, 1e-9)
            assertEquals(36.6761, request.destinationLatitudeDeg, 1e-9)
            assertEquals(117.1304, request.destinationLongitudeDeg, 1e-9)
            assertFalse("the first plan is not a reroute", request.reroute)
            assertEquals(NavigationState.Planning, core.snapshot().navigationStateOrNull())
        }
    }

    @Test
    fun acceptingARouteProducesANavigatingSnapshotWithProjectedManeuver() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Online)
            core.beginNavigation(
                Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            val requestId = core.pushFix(goodFix(0, 1_000))
                .first { it.isRouteRequest }
                .requestId

            core.acceptRoute(route(), requestId = requestId, receivedAtMs = 1_050)
            val snapshot = core.snapshot()

            assertEquals(NavigationState.Navigating, snapshot.navigationStateOrNull())
            assertEquals("android-instrumented-route", snapshot.routeId)
            assertEquals(1, snapshot.routeGeneration)
            assertTrue(snapshot.hasUsableFix)
            assertFalse("a fresh fix is not stale", snapshot.gnssStale)
            assertEquals(1_000.0, snapshot.totalDistanceM, 1e-6)
            assertTrue(snapshot.remainingDistanceM <= snapshot.totalDistanceM)

            // The maneuver list is projected by the shared core; UTF-8 must
            // survive the JNI hop unchanged.
            assertTrue(snapshot.hasNextManeuver)
            assertEquals(ManeuverType.Right, snapshot.maneuverTypeOrNull())
            assertEquals(1, snapshot.maneuverId)
            assertEquals("东长安街", snapshot.roadName)
            assertEquals("前方路口右转", snapshot.instruction)
            assertEquals(300.0, snapshot.distanceToManeuverM, 0.001)

            // Traffic look-ahead classifies the worst level inside 5 km.
            assertEquals(TrafficLevel.Slow, snapshot.trafficLevelOrNull())

            // The accepted fix is projected immediately, so the bounded route
            // window is present without waiting for another fix.
            assertTrue("route view must be committed", snapshot.hasRouteView)
            assertTrue(
                "route view needs at least two points",
                snapshot.routeViewPointCount >= 2,
            )
            assertEquals(
                snapshot.routeViewPointCount,
                snapshot.routeViewPoints().size,
            )
        }
    }

    @Test
    fun laterFixesAdvanceProgressMonotonically() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Online)
            core.beginNavigation(
                Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            val requestId = core.pushFix(goodFix(0, 1_000))
                .first { it.isRouteRequest }
                .requestId
            core.acceptRoute(route(), requestId = requestId, receivedAtMs = 1_050)
            val initial = core.snapshot().routeProgressM

            core.pushFix(goodFix(1, 2_000))
            val afterSecond = core.snapshot().routeProgressM
            core.pushFix(goodFix(2, 3_000))
            val afterThird = core.snapshot().routeProgressM

            assertTrue("progress must not go backwards", afterSecond >= initial)
            assertTrue("progress must not go backwards", afterThird >= afterSecond)
            assertTrue("progress must advance along a forward ride", afterThird > initial)
        }
    }

    @Test
    fun pageSelectionIsReportedInTheSnapshot() {
        MotoNavCore().use { core ->
            core.selectDisplayPage(DisplayPage.Speed)
            assertEquals(DisplayPage.Speed.code, core.snapshot().displayPage)
            core.selectDisplayPage(DisplayPage.Compass)
            assertEquals(DisplayPage.Compass.code, core.snapshot().displayPage)
        }
    }

    @Test
    fun cancelNavigationReturnsToIdleAndBlanksTheRouteView() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Online)
            core.beginNavigation(
                Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            val requestId = core.pushFix(goodFix(0, 1_000))
                .first { it.isRouteRequest }
                .requestId
            core.acceptRoute(route(), requestId = requestId, receivedAtMs = 1_050)
            assertTrue(core.snapshot().hasRouteView)

            core.cancelNavigation()
            val snapshot = core.snapshot()
            assertEquals(NavigationState.Idle, snapshot.navigationStateOrNull())
            assertFalse(snapshot.hasRouteView)
            assertEquals("", snapshot.routeId)
            assertFalse(snapshot.hasDestination)
            assertEquals(0.0, snapshot.routeProgressM, 1e-9)
        }
    }

    @Test
    fun aSilentFixStreamIsReportedAsStaleAndFreezesTheGauge() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Online)
            core.beginNavigation(
                Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            val requestId = core.pushFix(goodFix(0, 1_000))
                .first { it.isRouteRequest }
                .requestId
            core.acceptRoute(route(), requestId = requestId, receivedAtMs = 1_050)
            assertFalse(core.snapshot().gnssStale)

            // Push past the 5 s staleness budget with no new fix. NavCore keeps
            // the last position but must mark it stale and zero the speed so the
            // round display cannot keep showing motion.
            core.tick(60_000)
            val snapshot = core.snapshot()
            assertTrue("gnss_stale must be raised", snapshot.gnssStale)
            assertEquals(0.0, snapshot.speedMps, 1e-9)
        }
    }

    @Test
    fun aPoorAccuracyFixIsNeverTreatedAsUsable() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Online)
            core.beginNavigation(
                Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            // maximum_usable_accuracy_m defaults to 50 m.
            val commands = core.pushFix(
                MotoGnssFix(
                    latitudeDeg = fixLat[0],
                    longitudeDeg = fixLon[0],
                    accuracyM = 500.0,
                    speedMps = 0.0,
                    headingDeg = 0.0,
                    timestampMs = 2_000,
                ),
            )
            assertFalse(core.snapshot().hasUsableFix)
            assertTrue(
                "a rejected fix must not trigger planning",
                commands.none { it.isRouteRequest },
            )
            assertEquals(NavigationState.Acquiring, core.snapshot().navigationStateOrNull())
        }
    }

    @Test
    fun aStaleRouteResponseIsDiscarded() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Online)
            core.beginNavigation(
                Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            val requestId = core.pushFix(goodFix(0, 1_000))
                .first { it.isRouteRequest }
                .requestId
            // An older request id must not replace the active route.
            core.acceptRoute(route(), requestId = requestId + 99, receivedAtMs = 1_050)
            assertEquals("", core.snapshot().routeId)
            assertEquals(NavigationState.Planning, core.snapshot().navigationStateOrNull())
        }
    }

    @Test
    fun offlineNetworkDoesNotRequestRoutes() {
        MotoNavCore().use { core ->
            core.setNetworkState(NetworkState.Offline)
            core.beginNavigation(
                Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
            )
            val commands = core.pushFix(goodFix(0, 1_000))
            assertTrue(commands.none { it.isRouteRequest })
            assertEquals(NavigationState.Planning, core.snapshot().navigationStateOrNull())
        }
    }
}

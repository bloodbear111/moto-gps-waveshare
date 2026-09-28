package io.github.bloodbear111.motogps.gateway

import io.github.bloodbear111.motogps.protocol.ManeuverType
import io.github.bloodbear111.motogps.protocol.TrafficLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper is where a gateway payload becomes something the shared NavCore
 * accepts. These tests pin the two rules that are easy to get wrong: never
 * re-project GCJ-02 geometry, and never invent data the gateway does not have.
 */
class GatewayRouteMapperTest {

    private fun bundle(
        coordinateSystem: String = "GCJ-02",
        totalDistanceM: Double = 1_000.0,
        polyline: List<GeoPointDto> = listOf(
            GeoPointDto(longitudeDeg = 117.1275, latitudeDeg = 36.6749),
            GeoPointDto(longitudeDeg = 117.1290, latitudeDeg = 36.6757),
            GeoPointDto(longitudeDeg = 117.1310, latitudeDeg = 36.6765),
        ),
        maneuvers: List<ManeuverDto> = emptyList(),
        traffic: List<TrafficSegmentDto> = emptyList(),
    ) = RouteBundleDto(
        routeId = "amap-route-a",
        coordinateSystem = coordinateSystem,
        totalDistanceM = totalDistanceM,
        totalDurationS = 180,
        polyline = polyline,
        maneuvers = maneuvers,
        traffic = traffic,
    )

    @Test
    fun `geometry is copied without a second coordinate transform`() {
        val plan = GatewayRouteMapper.toRoutePlan(bundle())
        assertEquals(3, plan.pointCount())
        assertEquals(36.6749, plan.latitudesDeg[0], 1e-12)
        assertEquals(117.1275, plan.longitudesDeg[0], 1e-12)
        assertEquals(36.6765, plan.latitudesDeg[2], 1e-12)
        assertEquals(117.1310, plan.longitudesDeg[2], 1e-12)
    }

    @Test
    fun `speed limit is reported unknown because the gateway has no such feed`() {
        val plan = GatewayRouteMapper.toRoutePlan(bundle())
        assertEquals(
            "a fabricated limit would show a wrong number on the round display",
            0,
            plan.speedLimitKph,
        )
    }

    @Test
    fun `every documented maneuver string maps to its shared enum value`() {
        val expected = mapOf(
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
        expected.forEach { (wire, mapped) ->
            assertEquals("maneuver '$wire'", mapped, GatewayRouteMapper.maneuverType(wire))
        }
    }

    @Test
    fun `every documented traffic string maps to its shared enum value`() {
        val expected = mapOf(
            "unknown" to TrafficLevel.Unknown,
            "free_flow" to TrafficLevel.FreeFlow,
            "slow" to TrafficLevel.Slow,
            "congested" to TrafficLevel.Congested,
            "severe" to TrafficLevel.Severe,
        )
        expected.forEach { (wire, mapped) ->
            assertEquals("traffic '$wire'", mapped, GatewayRouteMapper.trafficLevel(wire))
        }
    }

    @Test
    fun `an unrecognised enum string degrades to Unknown instead of a guess`() {
        assertEquals(ManeuverType.Unknown, GatewayRouteMapper.maneuverType("merge_left"))
        assertEquals(TrafficLevel.Unknown, GatewayRouteMapper.trafficLevel("gridlock"))
    }

    @Test
    fun `maneuver fields survive mapping including UTF-8 text`() {
        val plan = GatewayRouteMapper.toRoutePlan(
            bundle(
                maneuvers = listOf(
                    ManeuverDto(
                        id = 7,
                        type = "roundabout",
                        routeOffsetM = 512.5,
                        roadName = "东长安街",
                        instruction = "从第二个出口驶出",
                        roundaboutExit = 2,
                    ),
                ),
            ),
        )
        val maneuver = plan.maneuvers.single()
        assertEquals(7, maneuver.id)
        assertEquals(ManeuverType.Roundabout, maneuver.type)
        assertEquals(512.5, maneuver.routeOffsetM, 1e-9)
        assertEquals("东长安街", maneuver.roadName)
        assertEquals("从第二个出口驶出", maneuver.instruction)
        assertEquals(2, maneuver.roundaboutExit)
    }

    @Test
    fun `a backwards traffic segment is dropped rather than shortening the rider ETA`() {
        val plan = GatewayRouteMapper.toRoutePlan(
            bundle(
                traffic = listOf(
                    TrafficSegmentDto(0.0, 400.0, "free_flow"),
                    TrafficSegmentDto(400.0, 400.0, "slow"),
                    TrafficSegmentDto(400.0, 1_000.0, "congested"),
                ),
            ),
        )
        assertEquals(2, plan.traffic.size)
        assertEquals(TrafficLevel.FreeFlow, plan.traffic[0].level)
        assertEquals(TrafficLevel.Congested, plan.traffic[1].level)
    }

    @Test
    fun `remaining duration scales the complete-route duration to the rider progress`() {
        assertEquals(
            90,
            GatewayRouteMapper.remainingDurationSeconds(
                refreshedCompleteDurationS = 180,
                remainingDistanceM = 500.0,
                completeDistanceM = 1_000.0,
            ),
        )
        assertEquals(
            "no duration data must stay zero, not become a guess",
            0,
            GatewayRouteMapper.remainingDurationSeconds(0, 500.0, 1_000.0),
        )
        assertEquals(180, GatewayRouteMapper.remainingDurationSeconds(180, 2_000.0, 1_000.0))
    }

    @Test
    fun `a refreshed route only shares the baseline when geometry and length match`() {
        val current = bundle()
        assertTrue(GatewayRouteMapper.sharesCompleteRouteBaseline(current, bundle()))

        assertFalse(
            "a different length must not be allowed to move traffic offsets",
            GatewayRouteMapper.sharesCompleteRouteBaseline(
                current,
                bundle(totalDistanceM = 1_500.0),
            ),
        )
        assertFalse(
            "different geometry means the offsets no longer refer to this route",
            GatewayRouteMapper.sharesCompleteRouteBaseline(
                current,
                bundle(
                    polyline = listOf(
                        GeoPointDto(longitudeDeg = 117.2, latitudeDeg = 36.7),
                        GeoPointDto(longitudeDeg = 117.3, latitudeDeg = 36.8),
                    ),
                ),
            ),
        )
        assertFalse(
            "an unmarked coordinate system cannot be compared",
            GatewayRouteMapper.sharesCompleteRouteBaseline(
                bundle(coordinateSystem = "WGS84"),
                bundle(coordinateSystem = "WGS84"),
            ),
        )
    }
}

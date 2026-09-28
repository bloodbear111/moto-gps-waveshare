package io.github.bloodbear111.motogps.navigation

import io.github.bloodbear111.motogps.gateway.GatewayFailure
import io.github.bloodbear111.motogps.gateway.GatewayTransport
import io.github.bloodbear111.motogps.gateway.MotoGatewayClient
import io.github.bloodbear111.motogps.gateway.RouteBundleDto
import io.github.bloodbear111.motogps.gateway.GeoPointDto
import io.github.bloodbear111.motogps.protocol.TrafficLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the core-to-gateway bridge.
 *
 * The traffic cases encode the rule that is easiest to get wrong: a traffic
 * refresh must re-request the **active route's** endpoints, because its offsets
 * are measured from the start of the complete route.
 */
class NavCommandExecutorTest {

    private val baseUrl = "https://nav.example.com/api/"

    private class RecordingTransport(
        private val responder: (String) -> GatewayTransport.Response,
    ) : GatewayTransport {
        val bodies = mutableListOf<String>()

        override fun get(url: String): GatewayTransport.Response = responder(url)

        override fun postJson(url: String, body: String): GatewayTransport.Response {
            bodies += body
            return responder(url)
        }
    }

    private fun executor(
        transport: RecordingTransport,
        baseUrlProvider: () -> String? = { baseUrl },
        nowMs: Long = 10_000L,
    ) = NavCommandExecutor(
        gateway = MotoGatewayClient(transport),
        gatewayBaseUrl = baseUrlProvider,
        clock = { nowMs },
    )

    private fun routeCommand(
        // Matches ROUTE_JSON's echoed request_id, so the client's stale-response
        // guard accepts the reply.
        requestId: Int = 9,
        reroute: Boolean = false,
    ) = MotoNavCommand(
        typeName = MotoNavCommand.TYPE_REQUEST_ROUTE,
        requestId = requestId,
        originLongitudeDeg = 117.12795,
        originLatitudeDeg = 36.6751,
        destinationLongitudeDeg = 117.1304,
        destinationLatitudeDeg = 36.6761,
        reroute = reroute,
        routeId = "",
    )

    private fun trafficCommand(requestId: Int = 9, routeId: String = "amap-route-a") =
        MotoNavCommand(
            typeName = MotoNavCommand.TYPE_REQUEST_TRAFFIC,
            requestId = requestId,
            originLongitudeDeg = 0.0,
            originLatitudeDeg = 0.0,
            destinationLongitudeDeg = 0.0,
            destinationLatitudeDeg = 0.0,
            reroute = false,
            routeId = routeId,
        )

    private fun bundle(
        routeId: String = "amap-route-a",
        totalDistanceM: Double = 1_000.0,
        traffic: String = TRAFFIC_JSON,
    ) = RouteBundleDto(
        routeId = routeId,
        coordinateSystem = "GCJ-02",
        totalDistanceM = totalDistanceM,
        totalDurationS = 180,
        polyline = listOf(
            GeoPointDto(117.1275, 36.6749),
            GeoPointDto(117.1290, 36.6757),
            GeoPointDto(117.1310, 36.6765),
        ),
        maneuvers = emptyList(),
        traffic = Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(
                io.github.bloodbear111.motogps.gateway.TrafficSegmentDto.serializer(),
            ),
            traffic,
        ),
    )

    private fun activeRoute() = ActiveRouteContext(
        routeId = "amap-route-a",
        bundle = bundle(),
        originWgs84 = Wgs84Point(latitudeDeg = 36.6751, longitudeDeg = 117.12795),
        destinationWgs84 = Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304),
    )

    // -----------------------------------------------------------------------
    // Route requests
    // -----------------------------------------------------------------------

    @Test
    fun `a successful route request yields a GCJ-02 plan for the core`() = runTest {
        val transport = RecordingTransport { ok(ROUTE_JSON) }
        val outcomes = executor(transport)
            .outcomes(listOf(routeCommand()), activeRoute = null)
            .toList()

        val loaded = outcomes.single() as NavCommandOutcome.RouteLoaded
        assertEquals(9, loaded.requestId)
        assertEquals("amap-route-a", loaded.plan.routeId)
        assertEquals(3, loaded.plan.pointCount())
        assertEquals(36.6749, loaded.plan.latitudesDeg[0], 1e-12)
    }

    @Test
    fun `a route request uses the command's own WGS84 endpoints`() = runTest {
        val transport = RecordingTransport { ok(ROUTE_JSON) }
        executor(transport).outcomes(listOf(routeCommand()), activeRoute = null).toList()

        val sent = Json.parseToJsonElement(transport.bodies.single()) as JsonObject
        val origin = sent["origin"] as JsonObject
        assertEquals("117.12795", origin["longitude_deg"]!!.jsonPrimitive.content)
        assertEquals("36.6751", origin["latitude_deg"]!!.jsonPrimitive.content)
        assertEquals("WGS84", origin["coordinate_system"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a reroute reports the active route as previous_route_id`() = runTest {
        val transport = RecordingTransport { ok(ROUTE_JSON) }
        executor(transport)
            .outcomes(listOf(routeCommand(reroute = true)), activeRoute = activeRoute())
            .toList()

        val sent = Json.parseToJsonElement(transport.bodies.single()) as JsonObject
        assertEquals("amap-route-a", sent["previous_route_id"]!!.jsonPrimitive.content)
        assertEquals("true", sent["is_reroute"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a first plan has no previous_route_id`() = runTest {
        val transport = RecordingTransport { ok(ROUTE_JSON) }
        executor(transport).outcomes(listOf(routeCommand()), activeRoute = null).toList()
        val sent = Json.parseToJsonElement(transport.bodies.single()) as JsonObject
        assertFalse(sent.containsKey("previous_route_id"))
    }

    @Test
    fun `an unconfigured gateway fails the route without inventing one`() = runTest {
        val transport = RecordingTransport { error("must not be called") }
        val outcomes = executor(transport, baseUrlProvider = { null })
            .outcomes(listOf(routeCommand()), activeRoute = null)
            .toList()

        val failed = outcomes.single() as NavCommandOutcome.RouteFailed
        assertEquals(GatewayFailure.NotConfigured, failed.failure)
        assertFalse("a missing gateway must not be retried forever", failed.retryable)
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun `a transport failure is reported as retryable`() = runTest {
        val transport = RecordingTransport { throw java.io.IOException("connection reset") }
        val outcomes = executor(transport)
            .outcomes(listOf(routeCommand()), activeRoute = null)
            .toList()

        val failed = outcomes.single() as NavCommandOutcome.RouteFailed
        assertTrue(failed.retryable)
        assertTrue(failed.failure is GatewayFailure.Transport)
    }

    @Test
    fun `a misconfigured gateway error is not retryable`() = runTest {
        val transport = RecordingTransport {
            GatewayTransport.Response(
                statusCode = 503,
                body = """
                    {"protocol_version":1,"request_id":9,
                     "error":{"code":"SERVER_MISCONFIGURED","message":"AMap is not configured",
                              "retryable":false}}
                """.trimIndent(),
            )
        }
        val outcomes = executor(transport)
            .outcomes(listOf(routeCommand()), activeRoute = null)
            .toList()

        val failed = outcomes.single() as NavCommandOutcome.RouteFailed
        assertFalse(failed.retryable)
    }

    // -----------------------------------------------------------------------
    // Traffic refresh
    // -----------------------------------------------------------------------

    @Test
    fun `a traffic refresh re-requests the active route endpoints, not the rider position`() =
        runTest {
            val transport = RecordingTransport { ok(ROUTE_JSON) }
            executor(transport)
                .outcomes(listOf(trafficCommand()), activeRoute = activeRoute())
                .toList()

            val sent = Json.parseToJsonElement(transport.bodies.single()) as JsonObject
            val origin = sent["origin"] as JsonObject
            // 117.12795 / 36.6751 are the route's original endpoints. The command
            // carried 0.0 / 0.0, which would have produced a different route.
            assertEquals("117.12795", origin["longitude_deg"]!!.jsonPrimitive.content)
            assertEquals("36.6751", origin["latitude_deg"]!!.jsonPrimitive.content)
            val destination = sent["destination"] as JsonObject
            assertEquals("117.1304", destination["longitude_deg"]!!.jsonPrimitive.content)
            assertEquals("false", sent["is_reroute"]!!.jsonPrimitive.content)
            assertEquals("amap-route-a", sent["previous_route_id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a matching traffic refresh yields usable segments and the complete duration`() =
        runTest {
            val transport = RecordingTransport { ok(ROUTE_JSON) }
            val outcomes = executor(transport)
                .outcomes(listOf(trafficCommand()), activeRoute = activeRoute())
                .toList()

            val loaded = outcomes.single() as NavCommandOutcome.TrafficLoaded
            assertEquals(9, loaded.requestId)
            assertEquals("amap-route-a", loaded.routeId)
            assertEquals(2, loaded.segments.size)
            assertEquals(TrafficLevel.FreeFlow, loaded.segments[0].level)
            assertEquals(TrafficLevel.Slow, loaded.segments[1].level)
            // Complete-route duration: the caller scales it to remaining distance.
            assertEquals(420, loaded.completeRouteDurationS)
            assertEquals(10_000L, loaded.observedAtMs)
        }

    @Test
    fun `a traffic refresh for a different route id is rejected`() = runTest {
        val transport = RecordingTransport { ok(ROUTE_JSON) }
        val outcomes = executor(transport)
            .outcomes(listOf(trafficCommand(routeId = "amap-route-old")), activeRoute = activeRoute())
            .toList()

        val failed = outcomes.single() as NavCommandOutcome.TrafficFailed
        assertTrue(failed.failure is GatewayFailure.Protocol)
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun `a traffic refresh with no active route is rejected instead of guessed`() = runTest {
        val transport = RecordingTransport { error("must not be called") }
        val outcomes = executor(transport)
            .outcomes(listOf(trafficCommand()), activeRoute = null)
            .toList()

        assertTrue(outcomes.single() is NavCommandOutcome.TrafficFailed)
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun `a traffic refresh whose geometry moved is rejected, not applied`() = runTest {
        // Same id and length but different geometry is exactly the AMap case the
        // baseline check exists for.
        val shifted = bundle().copy(
            polyline = listOf(
                GeoPointDto(117.2, 36.7),
                GeoPointDto(117.3, 36.8),
                GeoPointDto(117.4, 36.9),
            ),
        )
        val transport = RecordingTransport { ok(routeJsonFor(shifted)) }
        val outcomes = executor(transport)
            .outcomes(listOf(trafficCommand()), activeRoute = activeRoute())
            .toList()

        val failed = outcomes.single() as NavCommandOutcome.TrafficFailed
        assertTrue(failed.failure is GatewayFailure.Protocol)
        assertTrue(failed.failure.message!!.contains("geometry"))
    }

    @Test
    fun `a traffic refresh that returns another route id is rejected`() = runTest {
        val transport = RecordingTransport { ok(ROUTE_JSON.replace("amap-route-a", "amap-route-b")) }
        val outcomes = executor(transport)
            .outcomes(listOf(trafficCommand()), activeRoute = activeRoute())
            .toList()

        assertTrue(outcomes.single() is NavCommandOutcome.TrafficFailed)
    }

    @Test
    fun `an unknown command is ignored rather than blocking the core`() = runTest {
        val transport = RecordingTransport { error("must not be called") }
        val command = MotoNavCommand(
            typeName = "some_future_command",
            requestId = 3,
            originLongitudeDeg = 0.0,
            originLatitudeDeg = 0.0,
            destinationLongitudeDeg = 0.0,
            destinationLatitudeDeg = 0.0,
            reroute = false,
            routeId = "",
        )
        val outcomes = executor(transport).outcomes(listOf(command), activeRoute = null).toList()
        assertTrue(outcomes.single() is NavCommandOutcome.Ignored)
    }

    private fun ok(body: String) = GatewayTransport.Response(200, body)

    private fun routeJsonFor(bundle: RouteBundleDto): String = Json.encodeToString(
        io.github.bloodbear111.motogps.gateway.RouteEnvelopeDto.serializer(),
        io.github.bloodbear111.motogps.gateway.RouteEnvelopeDto(
            requestId = 9,
            route = bundle,
        ),
    )

    private companion object {
        const val TRAFFIC_JSON =
            "[{\"start_offset_m\":0.0,\"end_offset_m\":400.0,\"level\":\"free_flow\"}," +
                "{\"start_offset_m\":400.0,\"end_offset_m\":1000.0,\"level\":\"slow\"}]"

        // Not `const`: the body uses string interpolation and trimIndent().
        val ROUTE_JSON = """
            {"protocol_version":1,"request_id":9,
             "route":{"schema_version":1,"route_id":"amap-route-a","provider":"amap",
               "coordinate_system":"GCJ-02","generated_at_ms":1700000000000,
               "total_distance_m":1000.0,"total_duration_s":420,
               "polyline":[{"longitude_deg":117.1275,"latitude_deg":36.6749},
                 {"longitude_deg":117.129,"latitude_deg":36.6757},
                 {"longitude_deg":117.131,"latitude_deg":36.6765}],
               "maneuvers":[],
               "traffic":$TRAFFIC_JSON}}
        """.trimIndent()
    }
}

package io.github.bloodbear111.motogps.gateway

import io.github.bloodbear111.motogps.navigation.Wgs84Point
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the gateway client.
 *
 * Envelopes and error bodies below follow the schemas under `shared/protocol`
 * plus `backend/src/gateway.js`. `backend/src/validation.js` rejects unknown
 * request fields, so the request-shape assertions matter as much as parsing.
 */
class MotoGatewayClientTest {

    private val baseUrl = "https://nav.example.com/moto-gps/api/"

    private class FakeTransport(
        private val responder: (String, String, String?) -> GatewayTransport.Response,
    ) : GatewayTransport {
        val calls = mutableListOf<Triple<String, String, String?>>()

        override fun get(url: String): GatewayTransport.Response =
            responder("GET", url, null).also { calls += Triple("GET", url, null) }

        override fun postJson(url: String, body: String): GatewayTransport.Response =
            responder("POST", url, body).also { calls += Triple("POST", url, body) }
    }

    private fun ok(body: String) = GatewayTransport.Response(200, body)

    private fun origin() = Wgs84Point(latitudeDeg = 36.6751, longitudeDeg = 117.12795)
    private fun destination() = Wgs84Point(latitudeDeg = 36.6761, longitudeDeg = 117.1304)

    // -----------------------------------------------------------------------
    // Request shape
    // -----------------------------------------------------------------------

    @Test
    fun `route request carries exactly the documented WGS84 fields`() {
        val client = MotoGatewayClient()
        val body = client.encodeRouteRequest(
            RouteRequestDto(
                requestId = 41,
                origin = Wgs84PointDto(longitudeDeg = 116.397389, latitudeDeg = 39.908722),
                destination = Wgs84PointDto(longitudeDeg = 116.410886, latitudeDeg = 39.92015),
                isReroute = false,
            ),
        )
        val parsed = Json.parseToJsonElement(body) as JsonObject

        assertEquals(
            setOf("protocol_version", "request_id", "route_mode", "origin", "destination", "is_reroute"),
            parsed.keys,
        )
        assertEquals(1, parsed["protocol_version"]!!.jsonPrimitive.content.toInt())
        assertEquals(41, parsed["request_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("driving", parsed["route_mode"]!!.jsonPrimitive.content)
        assertEquals("false", parsed["is_reroute"]!!.jsonPrimitive.content)

        val origin = parsed["origin"] as JsonObject
        assertEquals(setOf("coordinate_system", "longitude_deg", "latitude_deg"), origin.keys)
        assertEquals("WGS84", origin["coordinate_system"]!!.jsonPrimitive.content)
    }

    @Test
    fun `optional route fields are omitted rather than sent as null`() {
        val client = MotoGatewayClient()
        val body = client.encodeRouteRequest(
            RouteRequestDto(
                requestId = 7,
                origin = Wgs84PointDto(longitudeDeg = 1.0, latitudeDeg = 2.0),
                destination = Wgs84PointDto(longitudeDeg = 3.0, latitudeDeg = 4.0),
                isReroute = true,
            ),
        )
        val parsed = Json.parseToJsonElement(body) as JsonObject
        assertFalse("previous_route_id must be omitted", parsed.containsKey("previous_route_id"))
        assertFalse("destination_poi_id must be omitted", parsed.containsKey("destination_poi_id"))
        assertEquals("true", parsed["is_reroute"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reroute request includes the previous route id when provided`() {
        val client = MotoGatewayClient()
        val body = client.encodeRouteRequest(
            RouteRequestDto(
                requestId = 8,
                origin = Wgs84PointDto(longitudeDeg = 1.0, latitudeDeg = 2.0),
                destination = Wgs84PointDto(longitudeDeg = 3.0, latitudeDeg = 4.0),
                isReroute = true,
                previousRouteId = "amap-1234",
            ),
        )
        val parsed = Json.parseToJsonElement(body) as JsonObject
        assertEquals("amap-1234", parsed["previous_route_id"]!!.jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------
    // Route responses
    // -----------------------------------------------------------------------

    @Test
    fun `route options are parsed and echoed request id is accepted`() = runTest {
        val transport = FakeTransport { _, _, _ ->
            ok(ROUTE_OPTIONS_JSON)
        }
        val client = MotoGatewayClient(transport)

        val result = client.requestRouteOptions(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        val routes = (result as GatewayResult.Success).value
        assertEquals(2, routes.size)
        assertEquals(41, routes[0].requestId)
        assertEquals("amap-route-a", routes[0].bundle.routeId)
        assertEquals(2_450.5, routes[0].bundle.totalDistanceM, 1e-9)
        assertEquals(3, routes[0].bundle.polyline.size)
        assertEquals("right", routes[0].bundle.maneuvers[0].type)
        assertEquals(2, routes[0].bundle.traffic.size)
        assertEquals("free_flow", routes[0].bundle.traffic[0].level)
        assertEquals("slow", routes[0].bundle.traffic[1].level)

        val call = transport.calls.single()
        assertEquals("POST", call.first)
        assertEquals("${baseUrl}v1/route-options", call.second)
    }

    @Test
    fun `single route request parses the route envelope`() = runTest {
        val transport = FakeTransport { _, _, _ -> ok(ROUTE_JSON) }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        val route = (result as GatewayResult.Success).value
        assertEquals("amap-route-a", route.bundle.routeId)
        assertEquals("${baseUrl}v1/routes", transport.calls.single().second)
    }

    @Test
    fun `a response for an older request id is dropped as stale`() = runTest {
        // The gateway echoes request_id on every reply precisely so that an
        // older answer cannot replace a newer route.
        val transport = FakeTransport { _, _, _ ->
            ok(ROUTE_JSON.replace("\"request_id\":41", "\"request_id\":40"))
        }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        val failure = (result as GatewayResult.Failure).failure
        assertEquals(GatewayFailure.Stale(41, 40), failure)
    }

    @Test
    fun `a route that is not GCJ-02 is rejected instead of being re-projected`() = runTest {
        val transport = FakeTransport { _, _, _ ->
            ok(ROUTE_JSON.replace("\"GCJ-02\"", "\"WGS84\""))
        }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        val failure = (result as GatewayResult.Failure).failure
        assertTrue(failure is GatewayFailure.Protocol)
        assertTrue(failure.message!!.contains("GCJ-02"))
    }

    @Test
    fun `a polyline beyond the schema cap is rejected rather than truncated`() = runTest {
        val points = (0 until MotoGatewayClient.MAXIMUM_POLYLINE_POINTS + 1)
            .joinToString(",") { "{\"longitude_deg\":117.1,\"latitude_deg\":36.6}" }
        val transport = FakeTransport { _, _, _ ->
            ok(ROUTE_JSON.replace(POLYLINE_JSON, "[$points]"))
        }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        assertTrue((result as GatewayResult.Failure).failure is GatewayFailure.Protocol)
    }

    @Test
    fun `an unconfigured gateway reports NotConfigured instead of guessing a host`() = runTest {
        val transport = FakeTransport { _, _, _ -> error("must not be called") }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = null,
            requestId = 1,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        assertEquals(
            GatewayFailure.NotConfigured,
            (result as GatewayResult.Failure).failure,
        )
        assertTrue(transport.calls.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Errors
    // -----------------------------------------------------------------------

    @Test
    fun `a structured service error keeps code, retryability and Retry-After`() = runTest {
        val transport = FakeTransport { _, _, _ ->
            GatewayTransport.Response(
                statusCode = 429,
                body = """
                    {"protocol_version":1,"request_id":null,
                     "error":{"code":"RATE_LIMITED","message":"too many route requests","retryable":true}}
                """.trimIndent(),
                retryAfterSeconds = 60,
            )
        }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        val failure = (result as GatewayResult.Failure).failure as GatewayFailure.Service
        assertEquals(429, failure.httpStatus)
        assertEquals("RATE_LIMITED", failure.code)
        assertTrue(failure.retryable)
        assertEquals(60, failure.retryAfterSeconds)
    }

    @Test
    fun `a disabled gateway error is surfaced verbatim`() = runTest {
        val transport = FakeTransport { _, _, _ ->
            GatewayTransport.Response(
                statusCode = 503,
                body = """
                    {"protocol_version":1,"request_id":41,
                     "error":{"code":"SERVER_MISCONFIGURED",
                              "message":"AMap live navigation is not configured",
                              "retryable":false}}
                """.trimIndent(),
            )
        }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        val failure = (result as GatewayResult.Failure).failure as GatewayFailure.Service
        assertEquals("SERVER_MISCONFIGURED", failure.code)
        assertFalse("a misconfiguration must not be retried as transient", failure.retryable)
    }

    @Test
    fun `a malformed success body is a protocol failure, not a silent empty route`() = runTest {
        val transport = FakeTransport { _, _, _ -> ok("{\"unexpected\":true}") }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        assertTrue((result as GatewayResult.Failure).failure is GatewayFailure.Protocol)
    }

    @Test
    fun `a transport failure is reported as unreachable`() = runTest {
        val transport = FakeTransport { _, _, _ -> throw java.io.IOException("TLS handshake failed") }
        val client = MotoGatewayClient(transport)

        val result = client.requestRoute(
            baseUrl = baseUrl,
            requestId = 41,
            originWgs84 = origin(),
            destinationWgs84 = destination(),
        )

        val failure = (result as GatewayResult.Failure).failure
        assertTrue(failure is GatewayFailure.Transport)
    }

    // -----------------------------------------------------------------------
    // Places
    // -----------------------------------------------------------------------

    @Test
    fun `place search sends the nearby bias as a WGS84 pair`() = runTest {
        val transport = FakeTransport { _, _, _ -> ok(PLACES_JSON) }
        val client = MotoGatewayClient(transport)

        val result = client.searchPlaces(
            baseUrl = baseUrl,
            keywords = "奥体中心",
            originWgs84 = origin(),
        )

        val places = (result as GatewayResult.Success).value
        assertEquals(1, places.size)
        assertEquals("奥体中心", places[0].name)
        // The gateway already converted the provider POI back to WGS84.
        assertEquals(36.6711, places[0].location.latitudeDeg, 1e-9)
        assertEquals(117.1201, places[0].location.longitudeDeg, 1e-9)
        assertEquals(812, places[0].distanceM)

        val url = transport.calls.single().second
        assertTrue("keywords must be query encoded", url.contains("keywords=%E5%A5%A5%E4%BD%93%E4%B8%AD%E5%BF%83"))
        assertTrue(url.contains("longitude_deg=117.12795"))
        assertTrue(url.contains("latitude_deg=36.6751"))
        assertTrue(url.startsWith("${baseUrl}v1/places?"))
    }

    @Test
    fun `place search omits the bias when there is no fix yet`() = runTest {
        val transport = FakeTransport { _, _, _ -> ok(PLACES_JSON) }
        val client = MotoGatewayClient(transport)

        client.searchPlaces(baseUrl = baseUrl, keywords = "济南西站")

        val url = transport.calls.single().second
        assertFalse(url.contains("longitude_deg"))
        assertFalse(url.contains("latitude_deg"))
    }

    @Test
    fun `a one character keyword is rejected before any request`() = runTest {
        val transport = FakeTransport { _, _, _ -> error("must not be called") }
        val client = MotoGatewayClient(transport)

        val result = client.searchPlaces(baseUrl = baseUrl, keywords = "济")

        assertTrue((result as GatewayResult.Failure).failure is GatewayFailure.Protocol)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `health reports the documented capability flags`() = runTest {
        val transport = FakeTransport { _, _, _ ->
            ok(
                """
                {"status":"ok","ready_for_live_navigation":true,"provider":"amap",
                 "capabilities":{"route_planning":true,"route_traffic":true,
                   "road_speed_limits":false,"traffic_light_countdown":false,
                   "surrounding_map":true,"map_city_search":true},
                 "map_source":{"enabled":true}}
                """.trimIndent(),
            )
        }
        val client = MotoGatewayClient(transport)

        val health = (client.health(baseUrl) as GatewayResult.Success).value
        assertTrue(health.readyForLiveNavigation)
        assertEquals("amap", health.provider)
        assertFalse(
            "the current gateway has no speed-limit feed",
            health.capabilities.roadSpeedLimits,
        )
        assertFalse(
            "the current gateway has no signal countdown feed",
            health.capabilities.trafficLightCountdown,
        )
        assertTrue(health.capabilities.surroundingMap)
    }

    private companion object {
        const val POLYLINE_JSON =
            "[{\"longitude_deg\":117.1275,\"latitude_deg\":36.6749}," +
                "{\"longitude_deg\":117.129,\"latitude_deg\":36.6757}," +
                "{\"longitude_deg\":117.131,\"latitude_deg\":36.6765}]"

        val ROUTE_JSON = """
            {"protocol_version":1,"request_id":41,
             "route":{"schema_version":1,"route_id":"amap-route-a","provider":"amap",
               "coordinate_system":"GCJ-02","generated_at_ms":1700000000000,
               "total_distance_m":2450.5,"total_duration_s":420,
               "polyline":$POLYLINE_JSON,
               "maneuvers":[{"id":1,"type":"right","route_offset_m":300.0,
                 "road_name":"东长安街","instruction":"前方路口右转","roundabout_exit":0}],
               "traffic":[{"start_offset_m":0,"end_offset_m":400,"level":"free_flow"},
                 {"start_offset_m":400,"end_offset_m":2450.5,"level":"slow"}]}}
        """.trimIndent()

        val ROUTE_OPTIONS_JSON = """
            {"protocol_version":1,"request_id":41,"routes":[
              {"schema_version":1,"route_id":"amap-route-a","provider":"amap",
               "coordinate_system":"GCJ-02","generated_at_ms":1700000000000,
               "total_distance_m":2450.5,"total_duration_s":420,
               "polyline":$POLYLINE_JSON,
               "maneuvers":[{"id":1,"type":"right","route_offset_m":300.0,
                 "road_name":"东长安街","instruction":"前方路口右转","roundabout_exit":0}],
               "traffic":[{"start_offset_m":0,"end_offset_m":400,"level":"free_flow"},
                 {"start_offset_m":400,"end_offset_m":2450.5,"level":"slow"}]},
              {"schema_version":1,"route_id":"amap-route-b","provider":"amap",
               "coordinate_system":"GCJ-02","generated_at_ms":1700000000000,
               "total_distance_m":2780.0,"total_duration_s":465,
               "polyline":$POLYLINE_JSON,
               "maneuvers":[],
               "traffic":[]}
            ]}
        """.trimIndent()

        val PLACES_JSON = """
            {"protocol_version":1,"query":"奥体中心",
             "places":[{"id":"B0001","name":"奥体中心","address":"经十路",
               "city":"济南市","district":"历下区","display_area":"济南市 · 历下区",
               "location":{"longitude_deg":117.1201,"latitude_deg":36.6711},
               "distance_m":812}]}
        """.trimIndent()
    }
}

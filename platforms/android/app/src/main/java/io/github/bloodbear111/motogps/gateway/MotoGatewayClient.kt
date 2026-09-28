package io.github.bloodbear111.motogps.gateway

import io.github.bloodbear111.motogps.navigation.Wgs84Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/** A place returned by `GET /v1/places`. `location` is WGS84. */
data class GatewayPlace(
    val id: String,
    val name: String,
    val address: String,
    val displayArea: String,
    val location: Wgs84Point,
    val distanceM: Int?,
)

/** One route from a successful route request, still in provider GCJ-02. */
data class GatewayRoute(
    val requestId: Int,
    val bundle: RouteBundleDto,
)

/**
 * Client for the self-hosted navigation gateway.
 *
 * Endpoints, query parameters, envelopes and error shape follow
 * `backend/src/gateway.js`; nothing here invents an endpoint. The app talks to
 * `/healthz`, `/v1/places`, `/v1/route-options` and `/v1/routes` only.
 *
 * `request_id` is owned by the caller: the shared `NavCore` allocates it and
 * hands it over in a `NavCommand`. The client therefore never generates one,
 * and it rejects a response whose echoed id is not the one it sent — the
 * transport-level half of the "discard stale responses" rule. The core
 * enforces the other half.
 */
class MotoGatewayClient(
    private val transport: GatewayTransport = HttpUrlConnectionTransport(),
    private val json: Json = DEFAULT_JSON,
) {

    companion object {
        /**
         * Unknown response fields are ignored so a gateway that adds optional
         * data does not break older clients; missing required fields still fail
         * decoding. Nulls are omitted when encoding, because
         * `backend/src/validation.js` rejects unknown keys outright.
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            // `protocol_version` and `route_mode` carry defaults in the DTO but
            // are REQUIRED by backend/src/validation.js, so defaults must be
            // encoded. Optional fields stay omitted because explicitNulls is off.
            explicitNulls = false
            isLenient = false
            encodeDefaults = true
        }

        /** `backend/src/validation.js` / `amap-places.js` limits. */
        const val MINIMUM_KEYWORDS_LENGTH = 2
        const val MAXIMUM_KEYWORDS_LENGTH = 80
        const val MAXIMUM_REGION_LENGTH = 32

        /** `route-bundle.v1.schema.json` limits. */
        const val MAXIMUM_POLYLINE_POINTS = 8192
        const val MAXIMUM_MANEUVERS = 512
        const val MAXIMUM_TRAFFIC_SEGMENTS = 2048

        /** `route-options.v1.schema.json`: at most three candidate routes. */
        const val MAXIMUM_ROUTE_OPTIONS = 3

        const val GCJ02 = "GCJ-02"
    }

    /** `GET /healthz`. */
    suspend fun health(baseUrl: String?): GatewayResult<GatewayHealthDto> =
        call(baseUrl, "healthz", GatewayHealthDto.serializer()) { endpoint ->
            transport.get(endpoint)
        }

    /**
     * `GET /v1/places`.
     *
     * @param originWgs84 optional current position used as a nearby-search bias.
     *   The gateway requires longitude and latitude together, and they must be
     *   WGS84 because the gateway performs the GCJ-02 conversion for AMap.
     */
    suspend fun searchPlaces(
        baseUrl: String?,
        keywords: String,
        originWgs84: Wgs84Point? = null,
        region: String? = null,
    ): GatewayResult<List<GatewayPlace>> {
        val trimmed = keywords.trim()
        when {
            trimmed.length < MINIMUM_KEYWORDS_LENGTH ->
                return GatewayResult.Failure(
                    GatewayFailure.Protocol(
                        "keywords must contain at least $MINIMUM_KEYWORDS_LENGTH characters",
                    ),
                )

            trimmed.length > MAXIMUM_KEYWORDS_LENGTH ->
                return GatewayResult.Failure(
                    GatewayFailure.Protocol(
                        "keywords must not exceed $MAXIMUM_KEYWORDS_LENGTH characters",
                    ),
                )

            region != null && region.length > MAXIMUM_REGION_LENGTH ->
                return GatewayResult.Failure(
                    GatewayFailure.Protocol(
                        "region must not exceed $MAXIMUM_REGION_LENGTH characters",
                    ),
                )
        }

        val parameters = buildList {
            add("keywords" to trimmed)
            if (!region.isNullOrBlank()) add("region" to region.trim())
            if (originWgs84 != null) {
                add("longitude_deg" to originWgs84.longitudeDeg.toString())
                add("latitude_deg" to originWgs84.latitudeDeg.toString())
            }
        }
        return call(
            baseUrl = baseUrl,
            relativePath = "v1/places",
            serializer = PlacesEnvelopeDto.serializer(),
            parameters = parameters,
        ) { endpoint -> transport.get(endpoint) }
            .map { envelope ->
                envelope.places.map { place ->
                    GatewayPlace(
                        id = place.id,
                        name = place.name,
                        address = place.address,
                        displayArea = place.displayArea,
                        // The gateway already converted the provider POI back.
                        location = Wgs84Point(
                            latitudeDeg = place.location.latitudeDeg,
                            longitudeDeg = place.location.longitudeDeg,
                        ),
                        distanceM = place.distanceM,
                    )
                }
            }
    }

    /** `POST /v1/route-options`: up to three candidate driving routes. */
    suspend fun requestRouteOptions(
        baseUrl: String?,
        requestId: Int,
        originWgs84: Wgs84Point,
        destinationWgs84: Wgs84Point,
        isReroute: Boolean = false,
        previousRouteId: String? = null,
        destinationPoiId: String? = null,
    ): GatewayResult<List<GatewayRoute>> = postRouteRequest(
        baseUrl = baseUrl,
        relativePath = "v1/route-options",
        request = routeRequest(
            requestId = requestId,
            originWgs84 = originWgs84,
            destinationWgs84 = destinationWgs84,
            isReroute = isReroute,
            previousRouteId = previousRouteId,
            destinationPoiId = destinationPoiId,
        ),
    ) { body ->
        when (val envelope = decode(body, RouteOptionsEnvelopeDto.serializer())) {
            is GatewayResult.Failure -> envelope
            is GatewayResult.Success -> withStaleCheck(requestId, envelope.value.requestId) {
                when {
                    envelope.value.routes.isEmpty() -> GatewayResult.Failure(
                        GatewayFailure.Protocol("gateway returned no candidate routes"),
                    )

                    envelope.value.routes.size > MAXIMUM_ROUTE_OPTIONS -> GatewayResult.Failure(
                        GatewayFailure.Protocol(
                            "gateway returned ${envelope.value.routes.size} routes; " +
                                "the v1 contract allows $MAXIMUM_ROUTE_OPTIONS",
                        ),
                    )

                    else -> firstBundleFailure(envelope.value.routes)
                        ?.let { GatewayResult.Failure(it) }
                        ?: GatewayResult.Success(
                            envelope.value.routes.map {
                                GatewayRoute(envelope.value.requestId, it)
                            },
                        )
                }
            }
        }
    }

    /** `POST /v1/routes`: one route, used for planning and rerouting. */
    suspend fun requestRoute(
        baseUrl: String?,
        requestId: Int,
        originWgs84: Wgs84Point,
        destinationWgs84: Wgs84Point,
        isReroute: Boolean = false,
        previousRouteId: String? = null,
        destinationPoiId: String? = null,
    ): GatewayResult<GatewayRoute> = postRouteRequest(
        baseUrl = baseUrl,
        relativePath = "v1/routes",
        request = routeRequest(
            requestId = requestId,
            originWgs84 = originWgs84,
            destinationWgs84 = destinationWgs84,
            isReroute = isReroute,
            previousRouteId = previousRouteId,
            destinationPoiId = destinationPoiId,
        ),
    ) { body ->
        when (val envelope = decode(body, RouteEnvelopeDto.serializer())) {
            is GatewayResult.Failure -> envelope
            is GatewayResult.Success -> withStaleCheck(requestId, envelope.value.requestId) {
                validateBundle(envelope.value.route)?.let { GatewayResult.Failure(it) }
                    ?: GatewayResult.Success(
                        GatewayRoute(envelope.value.requestId, envelope.value.route),
                    )
            }
        }
    }

    /** The exact request body the gateway's strict validator accepts. */
    fun encodeRouteRequest(request: RouteRequestDto): String =
        json.encodeToString(RouteRequestDto.serializer(), request)

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun routeRequest(
        requestId: Int,
        originWgs84: Wgs84Point,
        destinationWgs84: Wgs84Point,
        isReroute: Boolean,
        previousRouteId: String?,
        destinationPoiId: String?,
    ) = RouteRequestDto(
        requestId = requestId,
        origin = Wgs84PointDto(
            longitudeDeg = originWgs84.longitudeDeg,
            latitudeDeg = originWgs84.latitudeDeg,
        ),
        destination = Wgs84PointDto(
            longitudeDeg = destinationWgs84.longitudeDeg,
            latitudeDeg = destinationWgs84.latitudeDeg,
        ),
        isReroute = isReroute,
        previousRouteId = previousRouteId,
        destinationPoiId = destinationPoiId,
    )

    private suspend fun <T> postRouteRequest(
        baseUrl: String?,
        relativePath: String,
        request: RouteRequestDto,
        decode: (String) -> GatewayResult<T>,
    ): GatewayResult<T> {
        val root = baseUrl ?: return GatewayResult.Failure(GatewayFailure.NotConfigured)
        val body = encodeRouteRequest(request)
        val endpoint = GatewayConfiguration.endpoint(root, relativePath)
        val response = try {
            withContext(Dispatchers.IO) { transport.postJson(endpoint, body) }
        } catch (error: Exception) {
            return GatewayResult.Failure(
                GatewayFailure.Transport(error.message ?: error::class.java.simpleName),
            )
        }
        if (response.statusCode !in 200..299) {
            return GatewayResult.Failure(serviceFailure(response))
        }
        return decode(response.body)
    }

    private suspend fun <T> call(
        baseUrl: String?,
        relativePath: String,
        serializer: KSerializer<T>,
        parameters: List<Pair<String, String>> = emptyList(),
        send: (String) -> GatewayTransport.Response,
    ): GatewayResult<T> {
        val root = baseUrl ?: return GatewayResult.Failure(GatewayFailure.NotConfigured)
        val endpoint = buildString {
            append(GatewayConfiguration.endpoint(root, relativePath))
            if (parameters.isNotEmpty()) {
                append('?')
                append(
                    parameters.joinToString("&") { (name, value) ->
                        "${encodeQueryComponent(name)}=${encodeQueryComponent(value)}"
                    },
                )
            }
        }
        val response = try {
            withContext(Dispatchers.IO) { send(endpoint) }
        } catch (error: Exception) {
            return GatewayResult.Failure(
                GatewayFailure.Transport(error.message ?: error::class.java.simpleName),
            )
        }
        if (response.statusCode !in 200..299) {
            return GatewayResult.Failure(serviceFailure(response))
        }
        return decode(response.body, serializer)
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>): GatewayResult<T> =
        try {
            GatewayResult.Success(json.decodeFromString(serializer, body))
        } catch (error: Exception) {
            GatewayResult.Failure(
                GatewayFailure.Protocol(error.message ?: error::class.java.simpleName),
            )
        }

    /**
     * Applies the stale-response rule before the caller ever sees the payload:
     * a reply carrying a different `request_id` than the one sent is dropped
     * rather than allowed to replace a newer route.
     */
    private inline fun <R> withStaleCheck(
        expectedRequestId: Int,
        receivedRequestId: Int?,
        crossinline transform: () -> GatewayResult<R>,
    ): GatewayResult<R> =
        if (receivedRequestId == expectedRequestId) {
            transform()
        } else {
            GatewayResult.Failure(
                GatewayFailure.Stale(expectedRequestId, receivedRequestId),
            )
        }

    private fun serviceFailure(response: GatewayTransport.Response): GatewayFailure {
        val parsed = try {
            json.decodeFromString(ErrorEnvelopeDto.serializer(), response.body)
        } catch (error: Exception) {
            null
        }
        return GatewayFailure.Service(
            httpStatus = response.statusCode,
            code = parsed?.error?.code ?: "HTTP_${response.statusCode}",
            detail = parsed?.error?.message ?: "gateway returned no error envelope",
            // An unparseable error body is only assumed retryable for 5xx.
            retryable = parsed?.error?.retryable ?: (response.statusCode >= 500),
            retryAfterSeconds = response.retryAfterSeconds,
        )
    }

    /**
     * Rejects a bundle outside the v1 schema limits. Truncating would silently
     * change the route the rider sees, so an oversized payload fails instead.
     */
    private fun validateBundle(bundle: RouteBundleDto): GatewayFailure? = when {
        bundle.schemaVersion != 1 ->
            GatewayFailure.Protocol("unsupported route schema_version ${bundle.schemaVersion}")

        bundle.coordinateSystem != GCJ02 ->
            GatewayFailure.Protocol(
                "route geometry must be GCJ-02, got '${bundle.coordinateSystem}'",
            )

        bundle.routeId.isEmpty() ->
            GatewayFailure.Protocol("route_id is empty")

        bundle.polyline.size < 2 ->
            GatewayFailure.Protocol("polyline has fewer than two points")

        bundle.polyline.size > MAXIMUM_POLYLINE_POINTS ->
            GatewayFailure.Protocol("polyline exceeds $MAXIMUM_POLYLINE_POINTS points")

        bundle.maneuvers.size > MAXIMUM_MANEUVERS ->
            GatewayFailure.Protocol("route exceeds $MAXIMUM_MANEUVERS maneuvers")

        bundle.traffic.size > MAXIMUM_TRAFFIC_SEGMENTS ->
            GatewayFailure.Protocol("route exceeds $MAXIMUM_TRAFFIC_SEGMENTS traffic segments")

        !bundle.totalDistanceM.isFinite() || bundle.totalDistanceM <= 0.0 ->
            GatewayFailure.Protocol("total_distance_m must be positive")

        else -> null
    }

    private fun firstBundleFailure(bundles: List<RouteBundleDto>): GatewayFailure? {
        for (bundle in bundles) {
            validateBundle(bundle)?.let { return it }
        }
        return null
    }

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}

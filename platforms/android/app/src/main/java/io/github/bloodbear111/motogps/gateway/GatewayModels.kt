package io.github.bloodbear111.motogps.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the existing gateway HTTP contract.
 *
 * Field names and optionality come from the JSON schemas under
 * `shared/protocol`, plus `backend/src/gateway.js` and
 * `backend/src/amap-transformer.js` — not from guesswork.
 * `backend/src/validation.js` rejects unknown request fields, so the request
 * models below deliberately contain only the documented keys.
 *
 * Coordinate rules enforced here by type:
 * * `origin` / `destination` in a request are **WGS84** (GNSS output).
 * * `polyline`, maneuver offsets and traffic offsets in a response are
 *   **GCJ-02** (`coordinate_system: "GCJ-02"`). They are never re-projected.
 */

@Serializable
data class Wgs84PointDto(
    @SerialName("coordinate_system") val coordinateSystem: String = "WGS84",
    @SerialName("longitude_deg") val longitudeDeg: Double,
    @SerialName("latitude_deg") val latitudeDeg: Double,
)

@Serializable
data class RouteRequestDto(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("request_id") val requestId: Int,
    @SerialName("route_mode") val routeMode: String = "driving",
    val origin: Wgs84PointDto,
    val destination: Wgs84PointDto,
    @SerialName("is_reroute") val isReroute: Boolean,
    @SerialName("previous_route_id") val previousRouteId: String? = null,
    @SerialName("destination_poi_id") val destinationPoiId: String? = null,
)

/**
 * A bare provider/consumer point. The owning model states which coordinate
 * system it is in, because the wire schema deliberately omits the marker on
 * responses: `polyline` is always GCJ-02 while `places[].location` is always
 * WGS84. Mixing them without converting is the mistake this contract prevents.
 */
@Serializable
data class GeoPointDto(
    @SerialName("longitude_deg") val longitudeDeg: Double,
    @SerialName("latitude_deg") val latitudeDeg: Double,
)

@Serializable
data class ManeuverDto(
    val id: Int,
    val type: String,
    @SerialName("route_offset_m") val routeOffsetM: Double,
    @SerialName("road_name") val roadName: String = "",
    val instruction: String = "",
    @SerialName("roundabout_exit") val roundaboutExit: Int = 0,
)

@Serializable
data class TrafficSegmentDto(
    @SerialName("start_offset_m") val startOffsetM: Double,
    @SerialName("end_offset_m") val endOffsetM: Double,
    val level: String,
)

@Serializable
data class RouteBundleDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("route_id") val routeId: String,
    val provider: String = "amap",
    @SerialName("coordinate_system") val coordinateSystem: String,
    @SerialName("generated_at_ms") val generatedAtMs: Long = 0,
    @SerialName("total_distance_m") val totalDistanceM: Double,
    @SerialName("total_duration_s") val totalDurationS: Int,
    /** GCJ-02, matching `coordinate_system` above. Never re-projected. */
    val polyline: List<GeoPointDto>,
    val maneuvers: List<ManeuverDto> = emptyList(),
    val traffic: List<TrafficSegmentDto> = emptyList(),
)

@Serializable
data class RouteEnvelopeDto(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("request_id") val requestId: Int,
    val route: RouteBundleDto,
)

@Serializable
data class RouteOptionsEnvelopeDto(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("request_id") val requestId: Int,
    val routes: List<RouteBundleDto>,
)

@Serializable
data class PlaceDto(
    val id: String = "",
    val name: String = "",
    val address: String = "",
    val city: String = "",
    val district: String = "",
    @SerialName("display_area") val displayArea: String = "",
    /** WGS84: the gateway already converted the provider POI back. */
    val location: GeoPointDto,
    @SerialName("distance_m") val distanceM: Int? = null,
)

@Serializable
data class PlacesEnvelopeDto(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    val query: String = "",
    val places: List<PlaceDto> = emptyList(),
)

@Serializable
data class GatewayErrorDto(
    val code: String,
    val message: String = "",
    val retryable: Boolean = true,
)

@Serializable
data class ErrorEnvelopeDto(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("request_id") val requestId: Int? = null,
    val error: GatewayErrorDto,
)

@Serializable
data class GatewayCapabilitiesDto(
    @SerialName("route_planning") val routePlanning: Boolean = false,
    @SerialName("route_traffic") val routeTraffic: Boolean = false,
    /**
     * Both are `false` in the current gateway. The client must not invent speed
     * limits or signal countdowns when these are off.
     */
    @SerialName("road_speed_limits") val roadSpeedLimits: Boolean = false,
    @SerialName("traffic_light_countdown") val trafficLightCountdown: Boolean = false,
    @SerialName("surrounding_map") val surroundingMap: Boolean = false,
    @SerialName("map_city_search") val mapCitySearch: Boolean = false,
)

@Serializable
data class GatewayHealthDto(
    val status: String = "",
    @SerialName("ready_for_live_navigation") val readyForLiveNavigation: Boolean = false,
    val provider: String = "",
    val capabilities: GatewayCapabilitiesDto = GatewayCapabilitiesDto(),
)

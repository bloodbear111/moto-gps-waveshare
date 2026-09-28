package io.github.bloodbear111.motogps.navigation

import io.github.bloodbear111.motogps.protocol.DisplayPage
import io.github.bloodbear111.motogps.protocol.MotoNativeLibrary
import io.github.bloodbear111.motogps.protocol.NetworkState

/**
 * Kotlin facade over the shared `moto::nav::NavApp`, which owns route matching,
 * progression, off-route confirmation and reroute scheduling.
 *
 * Android must not re-implement any of that: the same state machine drives the
 * ESP32 UI and the web simulator, and the firmware's displayed maneuvers are
 * computed from this snapshot.
 *
 * Calls must be serialised; [io.github.bloodbear111.motogps.navigation.NavigationSession]
 * confines them to a single coroutine dispatcher.
 */
class MotoNavCore : AutoCloseable {

    private var handle: Long = 0
    private val snapshotBuffer = MotoNavSnapshotBuffer()

    init {
        MotoNativeLibrary.requireLoaded()
        handle = nativeCreate()
        check(handle != 0L) { "native NavApp allocation failed" }
    }

    fun reset(): List<MotoNavCommand> = nativeReset(handle).toList()

    fun beginNavigation(destination: Wgs84Point): List<MotoNavCommand> =
        nativeBeginNavigation(handle, destination.longitudeDeg, destination.latitudeDeg)
            .toList()

    fun cancelNavigation(): List<MotoNavCommand> =
        nativeCancelNavigation(handle).toList()

    fun setNetworkState(state: NetworkState): List<MotoNavCommand> =
        nativeSetNetworkState(handle, state.code).toList()

    fun selectDisplayPage(page: DisplayPage): List<MotoNavCommand> =
        nativeSelectDisplayPage(handle, page.code).toList()

    /**
     * Pushes a WGS84 GNSS fix. The shared core converts to GCJ-02 for route
     * matching while retaining the raw fix for reroute requests, so callers must
     * never pre-convert.
     */
    fun pushFix(fix: MotoGnssFix): List<MotoNavCommand> = nativePushFix(
        handle,
        fix.longitudeDeg,
        fix.latitudeDeg,
        fix.accuracyM,
        fix.speedMps,
        fix.headingDeg,
        fix.timestampMs,
    ).toList()

    fun acceptRoute(
        route: MotoRoutePlan,
        requestId: Int,
        receivedAtMs: Long,
    ): List<MotoNavCommand> {
        val maneuverCount = route.maneuvers.size
        val trafficCount = route.traffic.size
        return nativeAcceptRoute(
            handle,
            route.routeId.toByteArray(Charsets.UTF_8),
            route.latitudesDeg,
            route.longitudesDeg,
            IntArray(maneuverCount) { route.maneuvers[it].id },
            IntArray(maneuverCount) { route.maneuvers[it].type.code },
            DoubleArray(maneuverCount) { route.maneuvers[it].routeOffsetM },
            Array(maneuverCount) {
                route.maneuvers[it].roadName.toByteArray(Charsets.UTF_8)
            },
            Array(maneuverCount) {
                route.maneuvers[it].instruction.toByteArray(Charsets.UTF_8)
            },
            IntArray(maneuverCount) { route.maneuvers[it].roundaboutExit },
            DoubleArray(trafficCount) { route.traffic[it].startOffsetM },
            DoubleArray(trafficCount) { route.traffic[it].endOffsetM },
            IntArray(trafficCount) { route.traffic[it].level.code },
            route.totalDistanceM,
            route.totalDurationS,
            route.speedLimitKph,
            route.generatedAtMs,
            requestId,
            receivedAtMs,
        ).toList()
    }

    fun rejectRoute(
        requestId: Int,
        retryable: Boolean,
        receivedAtMs: Long,
    ): List<MotoNavCommand> =
        nativeRejectRoute(handle, requestId, retryable, receivedAtMs).toList()

    fun acceptTraffic(
        routeId: String,
        segments: List<MotoTrafficSegment>,
        remainingDurationS: Int,
        requestId: Int,
        observedAtMs: Long,
    ): List<MotoNavCommand> = nativeAcceptTraffic(
        handle,
        routeId.toByteArray(Charsets.UTF_8),
        DoubleArray(segments.size) { segments[it].startOffsetM },
        DoubleArray(segments.size) { segments[it].endOffsetM },
        IntArray(segments.size) { segments[it].level.code },
        remainingDurationS,
        requestId,
        observedAtMs,
    ).toList()

    fun rejectTraffic(requestId: Int, receivedAtMs: Long): List<MotoNavCommand> =
        nativeRejectTraffic(handle, requestId, receivedAtMs).toList()

    fun tick(timestampMs: Long): List<MotoNavCommand> =
        nativeTick(handle, timestampMs).toList()

    /** Test/demo hook that forces the production reroute transition. */
    fun simulateDeviation(): List<MotoNavCommand> =
        nativeSimulateDeviation(handle).toList()

    fun snapshot(): MotoNavSnapshotBuffer {
        nativeSnapshot(handle, snapshotBuffer)
        return snapshotBuffer
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long): Array<MotoNavCommand>
    private external fun nativeBeginNavigation(
        handle: Long,
        longitudeDeg: Double,
        latitudeDeg: Double,
    ): Array<MotoNavCommand>

    private external fun nativeCancelNavigation(handle: Long): Array<MotoNavCommand>
    private external fun nativeSetNetworkState(handle: Long, state: Int): Array<MotoNavCommand>
    private external fun nativeSelectDisplayPage(handle: Long, page: Int): Array<MotoNavCommand>
    private external fun nativePushFix(
        handle: Long,
        longitudeDeg: Double,
        latitudeDeg: Double,
        accuracyM: Double,
        speedMps: Double,
        headingDeg: Double,
        timestampMs: Long,
    ): Array<MotoNavCommand>

    private external fun nativeAcceptRoute(
        handle: Long,
        routeIdUtf8: ByteArray,
        latitudesDeg: DoubleArray,
        longitudesDeg: DoubleArray,
        maneuverIds: IntArray,
        maneuverTypes: IntArray,
        maneuverOffsets: DoubleArray,
        maneuverRoadNames: Array<ByteArray>,
        maneuverInstructions: Array<ByteArray>,
        maneuverExits: IntArray,
        trafficStarts: DoubleArray,
        trafficEnds: DoubleArray,
        trafficLevels: IntArray,
        totalDistanceM: Double,
        totalDurationS: Int,
        speedLimitKph: Int,
        generatedAtMs: Long,
        requestId: Int,
        receivedAtMs: Long,
    ): Array<MotoNavCommand>

    private external fun nativeRejectRoute(
        handle: Long,
        requestId: Int,
        retryable: Boolean,
        receivedAtMs: Long,
    ): Array<MotoNavCommand>

    private external fun nativeAcceptTraffic(
        handle: Long,
        routeIdUtf8: ByteArray,
        starts: DoubleArray,
        ends: DoubleArray,
        levels: IntArray,
        remainingDurationS: Int,
        requestId: Int,
        observedAtMs: Long,
    ): Array<MotoNavCommand>

    private external fun nativeRejectTraffic(
        handle: Long,
        requestId: Int,
        receivedAtMs: Long,
    ): Array<MotoNavCommand>

    private external fun nativeTick(handle: Long, timestampMs: Long): Array<MotoNavCommand>
    private external fun nativeSimulateDeviation(handle: Long): Array<MotoNavCommand>
    private external fun nativeSnapshot(handle: Long, out: MotoNavSnapshotBuffer)
}

/** GNSS fix in WGS84, as delivered by the platform location source. */
data class MotoGnssFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val accuracyM: Double,
    val speedMps: Double,
    val headingDeg: Double,
    val timestampMs: Long,
)

/** Destination in WGS84. The gateway converts to GCJ-02 server-side. */
data class Wgs84Point(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
)

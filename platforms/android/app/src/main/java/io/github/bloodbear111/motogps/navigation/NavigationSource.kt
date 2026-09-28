package io.github.bloodbear111.motogps.navigation

import kotlinx.coroutines.flow.Flow

/**
 * Platform-independent location source.
 *
 * The shared `NavCore` decides whether a fix is usable (accuracy budget,
 * staleness); this interface only has to deliver raw WGS84 fixes with the
 * metadata that decision needs: accuracy, speed, course and a monotonic
 * timestamp. Dropping any of those would make the core unable to distinguish
 * "parked" from "stale" or "turned" from "jitter".
 *
 * Implementations must report the coordinate system honestly. Android's
 * `Location` objects are WGS84 (the platform never returns GCJ-02), so the
 * gateway — not the phone — performs the GCJ-02 conversion for AMap.
 */
interface NavigationSource {

    /** Emits fixes in WGS84 until the returned flow is cancelled. */
    fun fixes(): Flow<MotoGnssFix>

    /** Reasons a location source can fail before it produces fixes. */
    sealed class Failure(message: String) : Exception(message) {
        data object PermissionDenied :
            Failure("location permission was not granted")

        data object ProviderDisabled :
            Failure("no location provider is enabled")

        /** Google Play services are missing or too old to provide fused fixes. */
        data object PlayServicesUnavailable :
            Failure("fused location provider is unavailable")

        data class Unavailable(val reason: String) : Failure(reason)
    }
}

/**
 * True when a fix is too old or too imprecise to drive navigation.
 *
 * The authoritative check lives in the shared `NavCore`
 * (`maximum_usable_accuracy_m` = 50 m, `gnss_stale_after_ms` = 5000 ms). This
 * helper exists so the UI can explain *why* the display is not updating without
 * having to re-derive the core's thresholds, and it is deliberately kept in
 * sync with them by `LocationQualityTest`.
 */
object LocationQuality {

    /** `NavCoreConfig::maximum_usable_accuracy_m`. */
    const val MAXIMUM_USABLE_ACCURACY_M = 50.0

    /** `NavCoreConfig::gnss_stale_after_ms`. */
    const val STALE_AFTER_MS = 5_000L

    fun isUsable(fix: MotoGnssFix, nowMs: Long): Boolean =
        fix.accuracyM.isFinite() &&
            fix.accuracyM >= 0.0 &&
            fix.accuracyM <= MAXIMUM_USABLE_ACCURACY_M &&
            fix.latitudeDeg.isFinite() &&
            fix.longitudeDeg.isFinite() &&
            fix.timestampMs <= nowMs + STALE_AFTER_MS

    fun isStale(fix: MotoGnssFix?, nowMs: Long): Boolean =
        fix == null || nowMs - fix.timestampMs > STALE_AFTER_MS

    /**
     * Why a fix is being ignored, for display. Returns null when it is usable.
     * "Approximate only" and "stale" must stay distinguishable: one needs a
     * permission change, the other just needs time.
     */
    fun describeRejection(fix: MotoGnssFix?, nowMs: Long): Rejection? = when {
        fix == null -> Rejection.NoFixYet
        !fix.latitudeDeg.isFinite() || !fix.longitudeDeg.isFinite() ->
            Rejection.InvalidCoordinates

        // A provider that omits accuracy must not be treated as precise, and it
        // is a different situation from "reported but too wide".
        !fix.accuracyM.isFinite() || fix.accuracyM < 0.0 ->
            Rejection.UnknownAccuracy

        fix.accuracyM > MAXIMUM_USABLE_ACCURACY_M ->
            Rejection.PoorAccuracy(fix.accuracyM)

        isStale(fix, nowMs) -> Rejection.Stale
        else -> null
    }

    sealed interface Rejection {
        data object NoFixYet : Rejection
        data object InvalidCoordinates : Rejection
        data object Stale : Rejection
        data object UnknownAccuracy : Rejection
        data class PoorAccuracy(val accuracyM: Double) : Rejection
    }
}

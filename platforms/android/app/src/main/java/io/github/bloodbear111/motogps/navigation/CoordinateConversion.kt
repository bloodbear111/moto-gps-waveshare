package io.github.bloodbear111.motogps.navigation

import io.github.bloodbear111.motogps.protocol.MotoNativeLibrary

/**
 * GCJ-02 to WGS84, answered by the upstream `shared/coordinates` transform.
 *
 * The forward direction (WGS84 to GCJ-02) already exists in the shared C++ core
 * and is used by the gateway; this is its inverse, computed in the same
 * translation unit by iterating that function. Keeping it there is deliberate:
 * a Kotlin copy of the China-region offset maths would be a second source of
 * truth for the one thing in this app that silently corrupts positions when it
 * disagrees.
 *
 * Both endpoints of the pipeline are labelled:
 * * platform location and AMap's location SDK deliver **GCJ-02** in mainland
 *   China (AMap reports the system it used through `AMapLocation.getCoordType`);
 * * everything the phone hands to the core and the gateway is **WGS84**.
 *
 * Returns null when the native library is unavailable or the input is invalid,
 * and the caller must then refuse the fix rather than pass a shifted position on.
 */
object CoordinateConversion {

    /** @return `[longitudeDeg, latitudeDeg]` in WGS84, or null when unavailable. */
    fun gcj02ToWgs84(longitudeDeg: Double, latitudeDeg: Double): DoubleArray? {
        if (!MotoNativeLibrary.isLoaded) return null
        return try {
            nativeGcj02ToWgs84(longitudeDeg, latitudeDeg)
        } catch (error: Throwable) {
            null
        }
    }

    private external fun nativeGcj02ToWgs84(
        longitudeDeg: Double,
        latitudeDeg: Double,
    ): DoubleArray?
}

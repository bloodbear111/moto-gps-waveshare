package io.github.bloodbear111.motogps.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.bloodbear111.motogps.ble.BlePermissions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android location source that does **not** assume Google Play services.
 *
 * Many mainland-China Android phones ship without GMS, so the fused provider is
 * used only when `GoogleApiAvailability` confirms it is present. Otherwise the
 * platform `LocationManager` delivers GNSS fixes, which is a first-class path
 * rather than a degraded one.
 *
 * Fixes are reported in WGS84. Android never returns GCJ-02, so nothing is
 * converted here; the gateway converts before calling AMap.
 *
 * Only `ACCESS_FINE_LOCATION` counts as precise enough to navigate. A
 * coarse-only grant is surfaced as [NavigationSource.Failure.PermissionDenied]
 * instead of being passed on as a blurry position the rider might trust.
 */
class AndroidLocationSource(
    private val context: Context,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val minimumDistanceM: Float = 0f,
) : NavigationSource {

    companion object {
        /** 1 Hz is enough for the round display and keeps the radio cheap. */
        const val DEFAULT_INTERVAL_MS = 1_000L
    }

    /**
     * Permission is checked explicitly below and a revoked grant is caught as
     * `SecurityException`, so lint's blanket requirement is suppressed here.
     */
    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<MotoGnssFix> = callbackFlow {
        if (!BlePermissions.hasNavigationLocationPermission(context)) {
            close(NavigationSource.Failure.PermissionDenied)
            return@callbackFlow
        }

        if (fusedLocationAvailable()) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { trySend(it.toFix()) }
                }
            }
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs,
            )
                .setMinUpdateIntervalMillis(intervalMs)
                .setMinUpdateDistanceMeters(minimumDistanceM)
                .build()
            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (error: SecurityException) {
                close(NavigationSource.Failure.PermissionDenied)
                return@callbackFlow
            }
            awaitClose { client.removeLocationUpdates(callback) }
            return@callbackFlow
        }

        // No usable Play services: drive the platform provider directly.
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            close(NavigationSource.Failure.Unavailable("LocationManager is unavailable"))
            return@callbackFlow
        }
        val provider = chooseProvider(manager)
        if (provider == null) {
            close(NavigationSource.Failure.ProviderDisabled)
            return@callbackFlow
        }

        val listener = LocationListener { location -> trySend(location.toFix()) }
        try {
            manager.requestLocationUpdates(
                provider,
                intervalMs,
                minimumDistanceM,
                listener,
                Looper.getMainLooper(),
            )
        } catch (error: SecurityException) {
            close(NavigationSource.Failure.PermissionDenied)
            return@callbackFlow
        } catch (error: IllegalArgumentException) {
            close(
                NavigationSource.Failure.Unavailable(
                    error.message ?: "the provider rejected the request",
                ),
            )
            return@callbackFlow
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    /** Prefers GNSS; falls back to network only when GNSS is switched off. */
    private fun chooseProvider(manager: LocationManager): String? = when {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
            LocationManager.GPS_PROVIDER

        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
            LocationManager.NETWORK_PROVIDER

        else -> null
    }

    /**
     * `isGooglePlayServicesAvailable` returning SUCCESS is the only case where
     * the fused provider can be trusted. Missing, disabled, outdated or
     * currently-updating all fall back to the platform provider.
     */
    private fun fusedLocationAvailable(): Boolean = try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (error: Throwable) {
        false
    }
}

/**
 * Keeps the metadata the shared core needs. `elapsedRealtimeNanos` is used
 * rather than wall-clock time because `NavCore` only compares timestamps within
 * one session, and a clock adjustment would look like a stale fix (or one from
 * the future).
 */
internal fun Location.toFix(): MotoGnssFix = MotoGnssFix(
    latitudeDeg = latitude,
    longitudeDeg = longitude,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else Double.NaN,
    speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
    headingDeg = if (hasBearing()) bearing.toDouble() else 0.0,
    timestampMs = elapsedRealtimeNanos / 1_000_000L,
)

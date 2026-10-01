package io.github.bloodbear111.motogps.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
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
    /** Bring-up visibility: which providers were subscribed and which answered. */
    private val onEvent: ((String) -> Unit)? = null,
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

        // Platform providers first, and Play services only as the last resort.
        //
        // The order matters on phones sold in mainland China: many report
        // GoogleApiAvailability.SUCCESS (the packages are present) while the
        // fused backend delivers nothing at all. Trusting fused because it
        // "exists" produced a flow that was subscribed, permitted, visible in
        // Android's location-access log, and completely silent - which is
        // exactly what "no fix ever arrives" looked like here. The platform
        // providers are the ones a domestic phone actually fills.
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            manager?.getProviders(true)
                ?.filterTo(this) {
                    it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
                }
            // The passive provider replays fixes other apps requested. It cannot
            // be the only source (it is silent when nothing else is locating), but
            // on a phone where a map app is working it is a real source, and it
            // makes "someone is getting fixes and we are not" impossible.
            add(LocationManager.PASSIVE_PROVIDER)
        }

        if (providers.isNotEmpty()) {
            onEvent?.invoke("listening: ${providers.joinToString(",")}")
            var firstFixLogged = false
            val subscribed = mutableListOf<Pair<String, LocationListener>>()
            for (provider in providers) {
                // What the platform already knows, before waiting for a callback.
                // A null here plus no callback means the provider itself has
                // nothing, which is a different fault from a subscription that
                // was accepted and then went quiet.
                val lastKnown = runCatching { manager.getLastKnownLocation(provider) }
                    .getOrNull()
                onEvent?.invoke(
                    "lastKnown[$provider]=" + (
                        lastKnown?.let {
                            val ageS = (SystemClock.elapsedRealtime() -
                                it.elapsedRealtimeNanos / 1_000_000L) / 1_000L
                            "age=${ageS}s acc=${it.accuracy}"
                        } ?: "none"
                        ),
                )
                lastKnown?.let { trySend(it.toFix()) }

                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!firstFixLogged) {
                            firstFixLogged = true
                            onEvent?.invoke("first fix from $provider")
                        }
                        trySend(location.toFix())
                    }

                    @Deprecated("Required on API < 30")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) =
                        Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit
                }
                try {
                    manager.requestLocationUpdates(
                        provider,
                        intervalMs,
                        minimumDistanceM,
                        listener,
                        Looper.getMainLooper(),
                    )
                    subscribed += provider to listener
                } catch (error: SecurityException) {
                    close(NavigationSource.Failure.PermissionDenied)
                    return@callbackFlow
                } catch (error: IllegalArgumentException) {
                    // Provider disappeared between listing and subscribing; keep
                    // the others rather than failing the whole session.
                    onEvent?.invoke("provider $provider unavailable")
                }
            }
            if (subscribed.isNotEmpty()) {
                awaitClose {
                    subscribed.forEach { (_, listener) -> manager.removeUpdates(listener) }
                }
                return@callbackFlow
            }
        }

        // Nothing usable from the platform: Play services is the last resort.
        if (!fusedLocationAvailable()) {
            close(NavigationSource.Failure.ProviderDisabled)
            return@callbackFlow
        }
        onEvent?.invoke("listening: fused (play services)")
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

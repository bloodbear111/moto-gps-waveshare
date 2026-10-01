package io.github.bloodbear111.motogps.navigation

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.bloodbear111.motogps.ble.BlePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive

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
 * Only `ACCESS_FINE_LOCATION` counts as precise enough to navigate. A coarse
 * grant is surfaced as [NavigationSource.Failure.PermissionDenied] instead of
 * being passed on as a blurry position the rider might trust.
 *
 * ## Why the diagnostics live in here
 *
 * "Permission granted, the system logs a location access, and zero fixes ever
 * arrive" has several different causes and they need different answers: an
 * approximate grant, an app-op the OEM switched to ignore behind a grant that
 * still reads GRANTED, a subscription the location service accepted and then
 * never filled, and simply having no sky view. Each one is printed as an event
 * ([onEvent]) instead of all of them sharing the message "no fix yet":
 *
 * * `perm` / `appOps` - what the grant actually is.
 * * `providers` / `listening` - what the platform says is enabled.
 * * `lastKnown[...]` / `getCurrentLocation[...]` - the two separate read paths
 *   inside the location service, so "silent subscription" and "service has
 *   nothing" can be told apart.
 * * `satellites visible=... used=...` - zero visible is a sky-view problem;
 *   satellites visible and none used is a receiver problem.
 *
 * The subscription is also made twice at most: an OEM location client that
 * accepts a request and then goes quiet does not always recover by itself, and
 * re-registering is the recovery that does not need a reboot.
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

        /**
         * How long one subscription is given before it is dropped and made
         * again, and how many times that is worth doing. Bounded on purpose:
         * retrying forever would hide a real permission fault behind a stream
         * of attempts.
         */
        private const val RESUBSCRIBE_AFTER_MS = 20_000L
        private const val MAX_ATTEMPTS = 2

        /** `LocationManager.FUSED_PROVIDER` is API 31+; spelled out for API 26. */
        private const val PROVIDER_FUSED = "fused"

        /** GPS first, then whatever the OEM fuses, then cell/Wi-Fi, passive last. */
        private fun rank(provider: String): Int = when (provider) {
            LocationManager.GPS_PROVIDER -> 0
            PROVIDER_FUSED -> 1
            LocationManager.NETWORK_PROVIDER -> 2
            LocationManager.PASSIVE_PROVIDER -> 9
            else -> 3
        }
    }

    /** Outcome of one attempt at subscribing to the platform providers. */
    private enum class PlatformSubscribeResult {
        Subscribed,
        PermissionDenied,
        NothingSubscribable,
    }

    /**
     * Permission is checked explicitly below and a revoked grant is caught as
     * `SecurityException`, so lint's blanket requirement is suppressed here.
     */
    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<MotoGnssFix> = callbackFlow {
        onEvent?.invoke("perm " + BlePermissions.describeLocationPermission(context))
        onEvent?.invoke("appOps " + describeLocationOps(context))

        if (!BlePermissions.hasNavigationLocationPermission(context)) {
            close(NavigationSource.Failure.PermissionDenied)
            return@callbackFlow
        }

        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            onEvent?.invoke("no LocationManager service")
            close(NavigationSource.Failure.ProviderDisabled)
            return@callbackFlow
        }

        // The master switch is the one thing the app cannot work around, so
        // report it explicitly rather than leaving "no fix" ambiguous.
        onEvent?.invoke("locationEnabled=" + LocationManagerCompat.isLocationEnabled(manager))
        val allProviders = runCatching { manager.allProviders }.getOrNull().orEmpty()
        onEvent?.invoke("providers " + allProviders.joinToString(","))

        // Everything the platform currently has enabled, plus passive so a map
        // app that is already locating cannot be invisible to us.
        val providers = (
            runCatching { manager.getProviders(true) }.getOrNull().orEmpty() +
                LocationManager.PASSIVE_PROVIDER
            ).distinct().sortedBy(::rank)
        onEvent?.invoke("listening " + providers.joinToString(","))

        when (subscribeToPlatformProviders(manager, providers)) {
            PlatformSubscribeResult.Subscribed -> return@callbackFlow
            PlatformSubscribeResult.PermissionDenied -> {
                close(NavigationSource.Failure.PermissionDenied)
                return@callbackFlow
            }
            PlatformSubscribeResult.NothingSubscribable -> Unit
        }

        // Nothing usable from the platform: Play services is the last resort.
        if (!fusedLocationAvailable()) {
            close(NavigationSource.Failure.ProviderDisabled)
            return@callbackFlow
        }
        onEvent?.invoke("listening fused (play services)")
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
     * Platform-provider subscription. Returns only when the flow is cancelled
     * or when no provider could be subscribed at all; the returned value is
     * [PlatformSubscribeResult.Subscribed] in the cancellation case because the
     * caller has already run to completion by then.
     */
    @SuppressLint("MissingPermission")
    private suspend fun ProducerScope<MotoGnssFix>.subscribeToPlatformProviders(
        manager: LocationManager,
        providers: List<String>,
    ): PlatformSubscribeResult {
        // Platform callbacks are delivered off the main looper: a busy UI thread
        // must never be able to delay a fix, and trySend is thread-safe.
        val executor = Executors.newSingleThreadExecutor()
        val fixesSeen = AtomicInteger()
        val answered = ConcurrentHashMap<String, Boolean>()
        val subscribed = mutableListOf<Pair<String, LocationListener>>()
        val satellites = watchSatellites(manager, executor, fixesSeen)

        /** Single place that counts a fix, so every path logs the same way. */
        val accept: (String, Location) -> Unit = { provider, location ->
            answered[provider] = true
            if (fixesSeen.getAndIncrement() == 0) {
                onEvent?.invoke("first fix from $provider")
            }
            trySend(location.toFix())
        }

        try {
            var attempt = 0
            while (isActive && attempt < MAX_ATTEMPTS && fixesSeen.get() == 0) {
                attempt += 1
                answered.clear()
                if (attempt > 1) onEvent?.invoke("resubscribing (attempt $attempt)")

                for (provider in providers) {
                    // What the platform already knows, before waiting for a
                    // callback: a null here plus no callback means the provider
                    // itself has nothing, which is a different fault from a
                    // subscription that was accepted and then went quiet.
                    val lastKnown = runCatching { manager.getLastKnownLocation(provider) }
                        .getOrNull()
                    onEvent?.invoke("lastKnown[$provider]=" + describeFix(lastKnown))
                    lastKnown?.let { accept(provider, it) }

                    val listener = listenerFor(provider, accept)
                    try {
                        subscribe(manager, provider, executor, listener)
                        subscribed += provider to listener
                    } catch (error: IllegalArgumentException) {
                        // Provider disappeared between listing and subscribing;
                        // keep the others rather than failing the whole session.
                        onEvent?.invoke("provider $provider unavailable")
                    }
                }

                if (subscribed.isEmpty()) {
                    onEvent?.invoke("no subscribable provider")
                    return PlatformSubscribeResult.NothingSubscribable
                }

                // Second, independent OS entry point. The listener subscription
                // and getCurrentLocation() are separate implementations inside
                // the location service, so one answering while the other stays
                // silent is a concrete difference rather than another guess.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    probeCurrentLocations(manager, providers, executor, accept)
                }

                if (attempt < MAX_ATTEMPTS) {
                    delay(RESUBSCRIBE_AFTER_MS)
                    if (fixesSeen.get() == 0) {
                        onEvent?.invoke(
                            "no callback in ${RESUBSCRIBE_AFTER_MS * attempt / 1000}s",
                        )
                        val silent = subscribed.map { it.first }
                            .filter { answered[it] != true }
                        onEvent?.invoke("silent: " + silent.joinToString(","))
                        // Drop and re-register below; a client the service has
                        // written off is exactly what this recovers.
                        subscribed.forEach { (_, listener) ->
                            runCatching { manager.removeUpdates(listener) }
                        }
                        subscribed.clear()
                    }
                }
            }
            awaitClose { }
            return PlatformSubscribeResult.Subscribed
        } catch (error: SecurityException) {
            return PlatformSubscribeResult.PermissionDenied
        } finally {
            subscribed.forEach { (_, listener) ->
                runCatching { manager.removeUpdates(listener) }
            }
            satellites?.let { runCatching { manager.unregisterGnssStatusCallback(it) } }
            executor.shutdown()
        }
    }

    /**
     * Registers one provider.
     *
     * On API 31+ the explicit `android.location.LocationRequest` overload is
     * used, so the requested quality is stated rather than inferred from the
     * provider name. The passive provider keeps the low-power legacy request:
     * subscribing to it must never be able to raise the accuracy another app
     * asked for.
     */
    @SuppressLint("MissingPermission")
    private fun subscribe(
        manager: LocationManager,
        provider: String,
        executor: Executor,
        listener: LocationListener,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            provider != LocationManager.PASSIVE_PROVIDER
        ) {
            val quality = if (provider == LocationManager.NETWORK_PROVIDER) {
                android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
            } else {
                android.location.LocationRequest.QUALITY_HIGH_ACCURACY
            }
            // The provider-taking Builder overload is hidden API; the public
            // entry point passes the provider name as a separate argument and
            // the platform stamps it into the request.
            val request = android.location.LocationRequest.Builder(intervalMs)
                .setQuality(quality)
                .setMinUpdateIntervalMillis(intervalMs)
                .setMinUpdateDistanceMeters(minimumDistanceM)
                .build()
            manager.requestLocationUpdates(provider, request, executor, listener)
        } else {
            manager.requestLocationUpdates(
                provider,
                intervalMs,
                minimumDistanceM,
                listener,
                Looper.getMainLooper(),
            )
        }
    }

    private fun listenerFor(
        provider: String,
        accept: (String, Location) -> Unit,
    ): LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(provider, location)

        @Deprecated("Required below API 30; the platform stopped calling it in 30")
        override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(p: String) = Unit

        override fun onProviderDisabled(p: String) {
            onEvent?.invoke("provider $p disabled")
        }
    }

    /**
     * One-shot location query, a separate implementation inside the platform
     * from the listener subscription, fired once per provider at subscribe time.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private fun probeCurrentLocations(
        manager: LocationManager,
        providers: List<String>,
        executor: Executor,
        accept: (String, Location) -> Unit,
    ) {
        for (provider in providers) {
            try {
                manager.getCurrentLocation(provider, null, executor) { location ->
                    onEvent?.invoke("getCurrentLocation[$provider]=" + describeFix(location))
                    if (location != null) accept(provider, location)
                }
            } catch (error: Throwable) {
                onEvent?.invoke(
                    "getCurrentLocation[$provider] ${error::class.java.simpleName}",
                )
            }
        }
    }

    /**
     * Satellite picture. "0 visible" and "12 visible, 0 used" are different
     * faults - no sky view versus a receiver that is not closing a fix - and the
     * phone is the only place where the difference can be seen.
     *
     * Only reported while there is still no fix, and only when it changes, so it
     * cannot drown the other diagnostics.
     */
    @SuppressLint("MissingPermission")
    private fun watchSatellites(
        manager: LocationManager,
        executor: Executor,
        fixesSeen: AtomicInteger,
    ): GnssStatus.Callback? {
        val lastLine = AtomicReference<String>()
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (fixesSeen.get() > 0) return
                var visible = 0
                var used = 0
                var bestSnr = 0f
                for (index in 0 until status.satelliteCount) {
                    visible += 1
                    if (status.usedInFix(index)) used += 1
                    bestSnr = maxOf(bestSnr, status.getCn0DbHz(index))
                }
                val line = "satellites visible=$visible used=$used snr=${bestSnr.toInt()}"
                if (lastLine.getAndSet(line) != line) onEvent?.invoke(line)
            }
        }
        return try {
            val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.registerGnssStatusCallback(executor, callback)
            } else {
                manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
                true
            }
            if (registered) callback else null
        } catch (error: Throwable) {
            null
        }
    }

    /**
     * MIUI/HyperOS can hold a runtime grant that still reads GRANTED while the
     * app-op behind it is set to ignore, which produces exactly "the system logs
     * a location access and no fix ever arrives". Printing the op keeps that
     * from being blamed on the sky.
     */
    private fun describeLocationOps(context: Context): String {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return "unavailable"

        fun check(op: String): String {
            val mode = try {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
            } catch (error: Throwable) {
                return "?"
            }
            return when (mode) {
                0 -> "allow"
                1 -> "ignore"
                2 -> "error"
                3 -> "default"
                4 -> "foreground"
                else -> "mode=$mode"
            }
        }

        return "fine=" + check(AppOpsManager.OPSTR_FINE_LOCATION) +
            " coarse=" + check(AppOpsManager.OPSTR_COARSE_LOCATION)
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

/** Age and accuracy of a fix, or "none". Used by every diagnostic line. */
private fun describeFix(location: Location?): String {
    if (location == null) return "none"
    val ageS = (SystemClock.elapsedRealtime() -
        location.elapsedRealtimeNanos / 1_000_000L) / 1_000L
    val accuracy = if (location.hasAccuracy()) location.accuracy.toInt().toString() else "?"
    return "age=${ageS}s acc=${accuracy}m"
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

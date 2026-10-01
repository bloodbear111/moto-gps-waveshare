package io.github.bloodbear111.motogps.navigation

import android.content.Context
import android.os.SystemClock
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Location from AMap's location SDK.
 *
 * Why this exists next to [AndroidLocationSource]: the platform location service
 * on a mainland-China phone can accept every fix request and answer none of them
 * (the on-device diagnostics showed all four providers subscribed, `appOp` and
 * permission fine, and not one callback, not even a `lastKnown` cache entry).
 * AMap's SDK carries its own network positioning - Wi-Fi and cell - which is what
 * actually works indoors in China, and it is the same provider the gateway
 * already routes against.
 *
 * Three things this deliberately does **not** do:
 * * it does not run without an explicit privacy consent ([AmapLocationSettings]),
 *   because the SDK sends the position to AMap;
 * * it does not use the gateway's Web Service key - an app needs an Android
 *   platform key bound to this package name and signing SHA1;
 * * it does not hand a GCJ-02 coordinate to the core as if it were WGS84. AMap
 *   answers in GCJ-02 in mainland China, and the gateway is the single place
 *   that converts WGS84 to GCJ-02. [AmapFixMapper] inverts it here, and refuses
 *   the fix when it cannot.
 *
 * Every result is also reported through [onEvent], so the navigation card shows
 * the SDK's own error codes instead of a generic "no fix".
 */
class AmapLocationSource(
    private val context: Context,
    private val apiKey: String,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val onEvent: ((String) -> Unit)? = null,
    private val mapper: AmapFixMapper = AmapFixMapper(CoordinateConversion::gcj02ToWgs84),
) : NavigationSource {

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L

        /** Long enough for a cold GNSS start, short enough to fall back. */
        private const val GPS_FIRST_TIMEOUT_MS = 15_000L
        private const val HTTP_TIMEOUT_MS = 8_000L
    }

    override fun fixes(): Flow<MotoGnssFix> = callbackFlow {
        onEvent?.invoke("amap key=" + AmapLocationSettings.describeKey(apiKey))

        // The SDK refuses to work until the host app has shown a privacy notice
        // and recorded the answer; the rider's answer is the switch in Settings.
        try {
            AMapLocationClient.updatePrivacyShow(context, true, true)
            AMapLocationClient.updatePrivacyAgree(context, true)
            AMapLocationClient.setApiKey(apiKey)
        } catch (error: Throwable) {
            onEvent?.invoke("amap init failed: ${error::class.java.simpleName}")
            close(NavigationSource.Failure.Unavailable("AMap SDK could not be initialised"))
            return@callbackFlow
        }

        val client = try {
            AMapLocationClient(context)
        } catch (error: Throwable) {
            onEvent?.invoke("amap client failed: ${error::class.java.simpleName}")
            close(NavigationSource.Failure.Unavailable("AMap location client unavailable"))
            return@callbackFlow
        }

        val option = AMapLocationClientOption().apply {
            setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
            setLocationPurpose(AMapLocationClientOption.AMapLocationPurpose.Transport)
            setInterval(intervalMs)
            setOnceLocation(false)
            setOnceLocationLatest(false)
            setNeedAddress(false)
            setGpsFirst(true)
            setGpsFirstTimeout(GPS_FIRST_TIMEOUT_MS)
            // A cached answer is allowed to arrive first (it is what makes an
            // indoor start usable) but its age is printed, and the core rejects
            // anything stale on its own terms.
            setLocationCacheEnable(true)
            setMockEnable(false)
            setSensorEnable(false)
            setWifiScan(true)
            setHttpTimeOut(HTTP_TIMEOUT_MS)
            setKillProcess(false)
        }

        var conversionsLogged = false
        var firstFixLogged = false

        val listener = AMapLocationListener { location ->
            val readout = location.toReadout()
            when (val outcome = mapper.map(readout, SystemClock.elapsedRealtime())) {
                is AmapFixOutcome.Rejected -> onEvent?.invoke("amap rejected: ${outcome.reason}")
                is AmapFixOutcome.Fix -> {
                    if (!firstFixLogged) {
                        firstFixLogged = true
                        onEvent?.invoke("amap first fix " + describeAmapFix(readout))
                    }
                    if (outcome.converted && !conversionsLogged) {
                        conversionsLogged = true
                        onEvent?.invoke("amap coords GCJ-02 -> WGS84")
                    }
                    trySend(outcome.fix)
                }
            }
        }

        client.setLocationOption(option)
        client.setLocationListener(listener)
        try {
            client.startLocation()
        } catch (error: Throwable) {
            onEvent?.invoke("amap start failed: ${error::class.java.simpleName}")
            runCatching { client.onDestroy() }
            close(NavigationSource.Failure.Unavailable("AMap location could not be started"))
            return@callbackFlow
        }
        onEvent?.invoke("amap started mode=Hight_Accuracy interval=${intervalMs}ms")

        awaitClose {
            // Stopping and destroying matters: a client left running keeps the
            // SDK's own service and the radio awake after navigation ends.
            runCatching { client.stopLocation() }
            runCatching { client.onDestroy() }
        }
    }
}

/** Reads an AMap result without assuming which fields the SDK filled in. */
private fun AMapLocation.toReadout(): AmapReadout = AmapReadout(
    longitudeDeg = longitude,
    latitudeDeg = latitude,
    coordinateType = coordType.orEmpty(),
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else Double.NaN,
    speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
    bearingDeg = if (hasBearing()) bearing.toDouble() else 0.0,
    timeMs = locationUtcTime,
    locationType = locationType,
    isMock = isMock,
    errorCode = errorCode,
    errorInfo = errorInfo.orEmpty(),
)

/** One diagnostic line per fix: source, coordinate system and accuracy. */
private fun describeAmapFix(readout: AmapReadout): String {
    val ageS = if (readout.timeMs > 0L) {
        ((System.currentTimeMillis() - readout.timeMs) / 1000L).coerceAtLeast(0L)
    } else {
        -1L
    }
    val accuracy = if (readout.accuracyM.isFinite()) {
        readout.accuracyM.toInt().toString()
    } else {
        "?"
    }
    return "type=${describeAmapLocationType(readout.locationType)}" +
        " coord=${readout.coordinateType.ifBlank { "?" }}" +
        " acc=${accuracy}m age=${ageS}s"
}

/** AMap's `getLocationType()` values, spelled out for the diagnostics. */
private fun describeAmapLocationType(type: Int): String = when (type) {
    0 -> "无效"
    1 -> "GPS"
    2 -> "前次定位"
    4 -> "缓存"
    5 -> "WiFi"
    6 -> "基站"
    7 -> "众包"
    8 -> "离线"
    9 -> "最后位置"
    else -> "type$type"
}

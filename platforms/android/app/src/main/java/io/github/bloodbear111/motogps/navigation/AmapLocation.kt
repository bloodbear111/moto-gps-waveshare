package io.github.bloodbear111.motogps.navigation

import android.content.Context
import androidx.core.content.edit

/**
 * One AMap `onLocationChanged` result, reduced to what the pipeline needs.
 *
 * `coordinateType` is AMap's own answer (`AMapLocation.getCoordType()`); it is
 * carried through instead of assumed, because a fix labelled WGS84 must not be
 * shifted a second time.
 */
data class AmapReadout(
    val longitudeDeg: Double,
    val latitudeDeg: Double,
    val coordinateType: String,
    val accuracyM: Double,
    val speedMps: Double,
    val bearingDeg: Double,
    val timeMs: Long,
    val locationType: Int,
    val isMock: Boolean,
    val errorCode: Int,
    val errorInfo: String,
)

/** What a readout became: a WGS84 fix, or a named reason it was refused. */
sealed interface AmapFixOutcome {
    data class Fix(val fix: MotoGnssFix, val converted: Boolean) : AmapFixOutcome

    data class Rejected(val reason: String) : AmapFixOutcome
}

/**
 * Turns an AMap result into the fix the shared core expects.
 *
 * Pure logic on purpose: the coordinate-system decision is the part that can be
 * wrong in a way nobody notices (a silent ~500 m shift), so it is unit tested
 * here instead of only being observable on a phone.
 *
 * Timestamps: [MotoGnssFix.timestampMs] is monotonic (`elapsedRealtime`), like
 * the platform source, because the core only compares timestamps inside one
 * session. AMap's own wall-clock `timeMs` is kept in the readout for the
 * diagnostics, where it shows how old a cached answer was.
 */
class AmapFixMapper(
    private val toWgs84: (Double, Double) -> DoubleArray?,
) {

    fun map(readout: AmapReadout, monotonicNowMs: Long): AmapFixOutcome {
        if (readout.errorCode != 0) {
            return AmapFixOutcome.Rejected(
                "AMap error ${readout.errorCode}: ${readout.errorInfo.ifBlank { "unknown" }}" +
                    amapErrorHint(readout.errorCode),
            )
        }
        if (readout.isMock) {
            return AmapFixOutcome.Rejected("AMap reported a mock location")
        }
        if (!readout.longitudeDeg.isFinite() || !readout.latitudeDeg.isFinite()) {
            return AmapFixOutcome.Rejected("AMap returned non-finite coordinates")
        }
        if (readout.longitudeDeg == 0.0 && readout.latitudeDeg == 0.0) {
            return AmapFixOutcome.Rejected("AMap returned 0,0 (no usable position)")
        }

        val coordinateType = readout.coordinateType.trim().uppercase()
        val converted = when (coordinateType) {
            "WGS84", "WGS-84" -> false
            "GCJ02", "GCJ-02" -> true
            // A missing type is refused as well, deliberately. Assuming WGS84
            // would silently shift every position by ~500 m when the SDK had
            // actually answered in GCJ-02, and that looks like a bad GPS rather
            // than a bug; a refusal shows up on the card instead.
            "" -> return AmapFixOutcome.Rejected("AMap did not report a coordinate type")
            // Anything else is unknown, and shifting or not shifting would both
            // be a guess about the rider's position.
            else -> return AmapFixOutcome.Rejected(
                "unknown coordinate type '${readout.coordinateType}'",
            )
        }

        val (longitude, latitude) = if (converted) {
            val result = toWgs84(readout.longitudeDeg, readout.latitudeDeg)
                ?: return AmapFixOutcome.Rejected(
                    "GCJ-02 to WGS84 conversion is unavailable; " +
                        "refusing a fix that would be ~500 m off",
                )
            if (result.size != 2) {
                return AmapFixOutcome.Rejected("coordinate conversion returned garbage")
            }
            result[0] to result[1]
        } else {
            readout.longitudeDeg to readout.latitudeDeg
        }

        return AmapFixOutcome.Fix(
            fix = MotoGnssFix(
                latitudeDeg = latitude,
                longitudeDeg = longitude,
                accuracyM = readout.accuracyM,
                speedMps = readout.speedMps,
                headingDeg = readout.bearingDeg,
                timestampMs = monotonicNowMs,
            ),
            converted = converted,
        )
    }
}

/**
 * The AMap codes that need an action rather than a shrug. Everything else keeps
 * the SDK's own text, because inventing a cause for an unknown code is worse
 * than showing the number.
 */
fun amapErrorHint(errorCode: Int): String = when (errorCode) {
    12 -> "（缺少定位权限）"
    13 -> "（定位失败，检查网络或 WLAN 扫描是否开启）"
    14 -> "（定位失败，检查 GPS 与室内环境）"
    20 -> "（AMap 参数错误）"
    26, 27 -> "（AMap Key 校验失败，检查控制台绑定）"
    32 -> "（系统定位服务被关闭）"
    33 -> "（定位失败，网络不可用）"
    35, 36 -> "（AMap Key 与包名/SHA1 不匹配，需在高德控制台核对）"
    else -> ""
}

/**
 * Where the AMap Android key and the privacy consent live.
 *
 * A key is required for the SDK to answer at all, and it must be an **Android
 * platform** key bound to this package name and the signing certificate SHA1 -
 * the Web Service key used by the gateway is a different product and returns
 * `USERKEY_PLAT_NOMATCH` when presented by an app.
 *
 * Consent is stored explicitly because the SDK sends the device's position to
 * AMap; the app only initialises it after the rider agrees.
 */
object AmapLocationSettings {

    const val PREFERENCES = "moto_gps"
    const val KEY_API_KEY = "MotoGPS.AmapApiKey.v1"
    const val KEY_CONSENT = "MotoGPS.AmapConsent.v1"
    const val PRIVACY_URL = "https://lbs.amap.com/pages/privacy/"

    sealed class KeyError(message: String) : Exception(message) {
        data object Empty : KeyError("input an AMap Android key first")
        data object Invalid : KeyError("an AMap key is 32 hexadecimal characters")
    }

    @Throws(KeyError::class)
    fun normalizeKey(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw KeyError.Empty
        if (trimmed.length != 32 || trimmed.any { it.digitToIntOrNull(16) == null }) {
            throw KeyError.Invalid
        }
        return trimmed.lowercase()
    }

    fun loadKey(context: Context): String? =
        preferences(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveKey(context: Context, key: String) {
        preferences(context).edit { putString(KEY_API_KEY, key) }
    }

    fun clearKey(context: Context) {
        preferences(context).edit { remove(KEY_API_KEY) }
    }

    fun isConsentGiven(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CONSENT, false)

    fun setConsent(context: Context, agreed: Boolean) {
        preferences(context).edit { putBoolean(KEY_CONSENT, agreed) }
    }

    /** Shows only the ends of the key, so it can be quoted in a bug report. */
    fun describeKey(key: String?): String = when {
        key == null -> "unset"
        key.length <= 8 -> "set"
        else -> key.take(4) + "..." + key.takeLast(4)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

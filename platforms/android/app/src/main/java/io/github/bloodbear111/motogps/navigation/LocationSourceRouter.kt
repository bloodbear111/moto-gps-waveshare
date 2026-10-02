package io.github.bloodbear111.motogps.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Which feed a fix came from. */
internal enum class LocationFeed {
    /** The phone's own location service: GNSS outdoors, network indoors. */
    System,

    /** AMap's SDK, whose Wi-Fi/cell positioning is what works indoors in China. */
    Amap,
}

/**
 * Decides whether a fix may be forwarded when two feeds are running.
 *
 * Both feeds are real, and they are not equally good at the same moment: AMap's
 * network answer is a coarse estimate (~30 m) of roughly the same instant, so
 * letting it through while the phone's own GNSS is streaming pulls the position
 * - and the map on the round display - around while the rider is moving.
 *
 * The rule is one-sided on purpose: the system feed always passes, and the
 * network feed steps aside only while the system feed has actually delivered
 * something recently. Indoors, where the system feed is the silent one, nothing
 * changes and AMap keeps the position alive - which is the whole reason the two
 * feeds run together instead of one being chosen at start-up.
 */
internal class LocationFixGate(
    private val systemPreferenceWindowMs: Long = DEFAULT_PREFERENCE_WINDOW_MS,
) {

    companion object {
        /**
         * Slightly longer than one fix interval: a single dropped GNSS callback
         * must not hand the position back to the coarser feed and back again.
         */
        const val DEFAULT_PREFERENCE_WINDOW_MS = 2_000L
    }

    private var lastSystemFixAtMs: Long? = null

    fun shouldForward(feed: LocationFeed, fix: MotoGnssFix): Boolean = when (feed) {
        LocationFeed.System -> {
            lastSystemFixAtMs = fix.timestampMs
            true
        }

        LocationFeed.Amap -> {
            val last = lastSystemFixAtMs
            last == null || fix.timestampMs - last > systemPreferenceWindowMs
        }
    }
}

/**
 * Runs the location feeds that are available and says which ones those are.
 *
 * The phone's own providers and AMap are collected **at the same time**. The
 * earlier version picked one at start-up, so a feed that answered rarely left
 * holes: the position froze between network fixes indoors and never used GNSS at
 * all when AMap had been selected. A feed that fails is reported and simply
 * stops contributing; the other one continues.
 */
class LocationSourceRouter(
    private val platform: NavigationSource,
    private val amap: () -> NavigationSource?,
    private val onEvent: (String) -> Unit,
) : NavigationSource {

    override fun fixes(): Flow<MotoGnssFix> = flow {
        val amapSource = amap()
        if (amapSource == null) {
            onEvent("source: 系统定位（高德未启用）")
            emitAll(platform.fixes())
            return@flow
        }

        onEvent("source: 系统 GNSS + 高德网络（双源同时运行）")
        val gate = LocationFixGate()
        val feeds = listOf(
            platform.fixes()
                .map { fix -> TaggedFix(LocationFeed.System, fix) }
                .catch { error ->
                    onEvent("系统定位停止：${error.message ?: error::class.java.simpleName}")
                },
            amapSource.fixes()
                .map { fix -> TaggedFix(LocationFeed.Amap, fix) }
                .catch { error ->
                    onEvent("高德定位停止：${error.message ?: error::class.java.simpleName}")
                },
        )
        merge(*feeds.toTypedArray()).collect { tagged ->
            if (gate.shouldForward(tagged.feed, tagged.fix)) emit(tagged.fix)
        }
    }

    private data class TaggedFix(val feed: LocationFeed, val fix: MotoGnssFix)
}

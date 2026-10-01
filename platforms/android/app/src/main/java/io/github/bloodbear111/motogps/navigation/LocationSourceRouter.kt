package io.github.bloodbear111.motogps.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Picks the location source when a session starts, and says which one it used.
 *
 * AMap comes first when it is configured, because it is the only source with
 * network positioning that works indoors in mainland China; the platform source
 * is the fallback. Both are real: nothing here can turn a missing fix into a
 * plausible-looking one, and a source that fails is reported before the next one
 * is tried rather than being papered over.
 */
class LocationSourceRouter(
    private val platform: NavigationSource,
    private val amap: () -> NavigationSource?,
    private val onEvent: (String) -> Unit,
) : NavigationSource {

    override fun fixes(): Flow<MotoGnssFix> = flow {
        val amapSource = amap()
        if (amapSource == null) {
            onEvent("source: system (AMap 未启用或未同意)")
            emitAll(platform.fixes())
            return@flow
        }

        onEvent("source: AMap")
        var failed: String? = null
        amapSource.fixes()
            .catch { error ->
                // Cancellation is not a failure and `catch` already rethrows it.
                failed = error.message ?: error::class.java.simpleName
            }
            .collect { fix -> emit(fix) }

        if (failed != null) {
            onEvent("AMap 失败（$failed），回退系统定位")
            emitAll(platform.fixes())
        }
    }
}

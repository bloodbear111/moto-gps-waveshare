package io.github.bloodbear111.motogps

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Process-scoped container.
 *
 * Nothing connection-related lives in an Activity: the BLE session, the shared
 * navigation core and the gateway client are owned here (or by a ViewModel that
 * outlives configuration changes), so rotating the phone or switching tasks does
 * not tear down a live link to the round display.
 */
class MotoGpsApplication : Application() {

    /** Application-lifetime scope for the transport and navigation session. */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

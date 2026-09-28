package io.github.bloodbear111.motogps.ble

/** Observable transport state, as surfaced to the UI and the nav session. */
sealed interface MotoBleConnectionState {

    data object Idle : MotoBleConnectionState

    data class Scanning(val found: Int) : MotoBleConnectionState

    data class Connecting(val name: String?, val address: String) : MotoBleConnectionState

    data class DiscoveringServices(val address: String) : MotoBleConnectionState

    /** Negotiating MTU, subscribing to TX and running the two-step handshake. */
    data class Handshaking(
        val address: String,
        val stage: String,
        val maximumFrameSize: Int,
    ) : MotoBleConnectionState

    /**
     * Both Ready messages matched. Business payloads may be sent now; a GATT
     * write acknowledgement alone never reaches this state.
     */
    data class ProtocolReady(
        val address: String,
        val maximumFrameSize: Int,
        val heartbeatIntervalMs: Int,
        val capabilities: Int,
    ) : MotoBleConnectionState

    /** Protocol link considered stale while the physical link may still be up. */
    data class Degraded(val address: String, val reason: String) : MotoBleConnectionState

    data class Disconnected(val address: String?, val reason: String) :
        MotoBleConnectionState

    data class Failed(val address: String?, val reason: String, val status: Int? = null) :
        MotoBleConnectionState
}

package io.github.bloodbear111.motogps.protocol

/**
 * Kotlin facade over the shared v1 wire codec in `shared/ble_protocol`.
 *
 * Every payload layout, CRC and fragmentation rule lives in the upstream C++;
 * this class only marshals arguments. That keeps the Android build byte-compatible
 * with the ESP32 firmware and the iOS app instead of maintaining a third
 * implementation of the protocol.
 *
 * Not thread safe: the underlying sequence counter and reassembler are stateful.
 * [io.github.bloodbear111.motogps.ble.MotoBleSession] owns one instance per
 * connection and serialises access.
 */
class MotoProtocolCodec(maximumFrameSize: Int = DEFAULT_MAXIMUM_FRAME_SIZE) :
    AutoCloseable {

    companion object {
        /** 512 is the protocol ceiling for a complete GATT characteristic value. */
        const val MAXIMUM_FRAME_SIZE_LIMIT = 512

        /** 12 bytes of frame overhead plus one payload byte. */
        const val MINIMUM_FRAME_SIZE = 13

        /** Used before MTU negotiation settles; matches an ATT MTU of 23. */
        const val DEFAULT_MAXIMUM_FRAME_SIZE = 20

        /**
         * Recommended value once the stack reports a larger MTU: 185 fits the
         * common 512-byte ATT MTU minus the 3-byte ATT header, capped by the
         * protocol's 512-byte value ceiling and the fixture's negotiated size.
         */
        const val RECOMMENDED_MAXIMUM_FRAME_SIZE = 185

        /** Framed value size for an ATT MTU, per spec section 2. */
        fun frameSizeForAttMtu(attMtu: Int): Int =
            (attMtu - ATT_HEADER_BYTES).coerceIn(
                MINIMUM_FRAME_SIZE,
                MAXIMUM_FRAME_SIZE_LIMIT,
            )

        private const val ATT_HEADER_BYTES = 3
    }

    private var handle: Long = 0

    val protocolMaximumFrameSize: Int
        get() = nativeMaximumFrameSize(handle)

    /** Sequence of the most recently produced logical message, 0 before the first. */
    val lastEncodedSequence: Int
        get() = nativeLastEncodedSequence(handle)

    init {
        MotoNativeLibrary.requireLoaded()
        handle = nativeCreate(
            maximumFrameSize.coerceIn(MINIMUM_FRAME_SIZE, MAXIMUM_FRAME_SIZE_LIMIT),
        )
        check(handle != 0L) { "native codec allocation failed" }
    }

    fun setMaximumFrameSize(words: Int) {
        nativeSetMaximumFrameSize(handle, words)
    }

    /**
     * Drops only device-to-phone fragment history. Outbound sequence numbers are
     * preserved so a re-handshake cannot look like a duplicate to the device.
     */
    fun resetInboundState() {
        nativeResetInbound(handle)
    }

    fun encodePhoneStarting(sessionId: Int): Array<ByteArray> =
        nativeEncodeConnection(handle, ConnectionState.Starting.code, sessionId)

    fun encodePhoneReady(sessionId: Int): Array<ByteArray> =
        nativeEncodeConnection(handle, ConnectionState.Ready.code, sessionId)

    fun encodePhoneDegraded(sessionId: Int): Array<ByteArray> =
        nativeEncodeConnection(handle, ConnectionState.Degraded.code, sessionId)

    fun encodePhoneClosing(sessionId: Int): Array<ByteArray> =
        nativeEncodeConnection(handle, ConnectionState.Closing.code, sessionId)

    /**
     * @param sessionElapsedMs elapsed time since this application session began.
     *   The spec forbids leaking phone uptime into the protocol: senders must
     *   use their own session clock, so callers pass
     *   [io.github.bloodbear111.motogps.protocol.BleSessionHeartbeatClock].
     */
    fun encodeHeartbeat(sessionId: Int, sessionElapsedMs: Long): Array<ByteArray> =
        nativeEncodeHeartbeat(handle, sessionId, (sessionElapsedMs and 0xFFFFFFFFL).toInt())

    fun encodeAck(
        acknowledgedSequence: Int,
        status: AckStatus,
        commandId: Int = 0,
    ): Array<ByteArray> =
        nativeEncodeAck(handle, acknowledgedSequence, status.code, commandId)

    fun encodeNavigationSnapshot(snapshot: MotoSnapshotInput): Array<ByteArray> =
        nativeEncodeNavigationSnapshot(handle, snapshot)

    /**
     * Encodes the bounded heading-up route window. Returns an empty array when
     * there is nothing to commit, matching the iOS bridge.
     */
    fun encodeRouteGeometry(
        routeId: String,
        routeGeneration: Int,
        originLatitudeE6: Int,
        originLongitudeE6: Int,
        latitudesE6: IntArray,
        longitudesE6: IntArray,
        pointCount: Int,
    ): Array<ByteArray> = nativeEncodeRouteGeometry(
        handle,
        routeId.toByteArray(Charsets.UTF_8),
        routeGeneration,
        originLatitudeE6,
        originLongitudeE6,
        latitudesE6,
        longitudesE6,
        pointCount,
    )

    fun encodeMapScene(scene: MotoMapSceneInput): Array<ByteArray> =
        nativeEncodeMapScene(handle, scene)

    /**
     * Feeds one characteristic value into the shared reassembler and decoder.
     * Returns `null` when the frame was rejected; [MotoProtocolException] is
     * thrown in that case so callers cannot mistake a dropped frame for data.
     */
    fun pushDeviceFrame(frame: ByteArray, receivedAtMs: Long): MotoInboundResult {
        val buffer = MotoInboundBuffer()
        val status = nativePushDeviceFrame(handle, frame, receivedAtMs, buffer)
        return when (status) {
            MotoInboundBuffer.STATUS_IN_PROGRESS -> MotoInboundResult.InProgress(buffer)
            MotoInboundBuffer.STATUS_DUPLICATE_FRAGMENT -> MotoInboundResult.DuplicateFragment
            MotoInboundBuffer.STATUS_DUPLICATE_MESSAGE ->
                MotoInboundResult.DuplicateMessage(buffer.sequence)
            MotoInboundBuffer.STATUS_COMPLETE -> MotoInboundResult.Complete(buffer.toMessage())
            else -> throw MotoProtocolException(
                "device frame rejected",
                buffer.errorCode,
                buffer.errorOffset,
            )
        }
    }

    fun routeToken(routeId: String): Int =
        nativeRouteToken(routeId.toByteArray(Charsets.UTF_8))

    /**
     * Runs the reviewed golden vectors from
     * `shared/protocol/fixtures/ble-navigation-v1.golden.txt`, compiled into the
     * library at build time, through the shared codec.
     */
    fun runGoldenSelfTest(): List<GoldenCheckResult> =
        nativeRunGoldenSelfTest().map(::parseGoldenLine)

    /** Service/RX/TX/CCCD UUIDs straight from the shared header. */
    fun gattUuids(): List<String> = nativeUuidStrings().toList()

    /**
     * Raw shared constants, used by tests to prove the Kotlin enums still match
     * the C++ numbering. Order is defined by [MotoProtocolCodec.nativeEnumValues].
     */
    fun rawEnumValues(): IntArray = nativeEnumValues()

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun parseGoldenLine(line: String): GoldenCheckResult {
        val parts = line.split('\t')
        return GoldenCheckResult(
            passed = parts.getOrNull(0) == "PASS",
            name = parts.getOrNull(1).orEmpty(),
            detail = parts.drop(2).joinToString("\t"),
        )
    }

    // --- natives -----------------------------------------------------------

    private external fun nativeCreate(maximumFrameSize: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetMaximumFrameSize(handle: Long, maximumFrameSize: Int)
    private external fun nativeMaximumFrameSize(handle: Long): Int
    private external fun nativeResetInbound(handle: Long)
    private external fun nativeLastEncodedSequence(handle: Long): Int
    private external fun nativeEncodeConnection(
        handle: Long,
        state: Int,
        sessionId: Int,
    ): Array<ByteArray>

    private external fun nativeEncodeHeartbeat(
        handle: Long,
        sessionId: Int,
        monotonicMs: Int,
    ): Array<ByteArray>

    private external fun nativeEncodeAck(
        handle: Long,
        sequence: Int,
        status: Int,
        commandId: Int,
    ): Array<ByteArray>

    private external fun nativeEncodeNavigationSnapshot(
        handle: Long,
        snapshot: MotoSnapshotInput,
    ): Array<ByteArray>

    private external fun nativeEncodeRouteGeometry(
        handle: Long,
        routeIdUtf8: ByteArray,
        generation: Int,
        originLatitudeE6: Int,
        originLongitudeE6: Int,
        latitudesE6: IntArray,
        longitudesE6: IntArray,
        pointCount: Int,
    ): Array<ByteArray>

    private external fun nativeEncodeMapScene(
        handle: Long,
        scene: MotoMapSceneInput,
    ): Array<ByteArray>

    private external fun nativePushDeviceFrame(
        handle: Long,
        frame: ByteArray,
        receivedAtMs: Long,
        out: MotoInboundBuffer,
    ): Int

    private external fun nativeRouteToken(routeIdUtf8: ByteArray): Int
    private external fun nativeRunGoldenSelfTest(): Array<String>
    private external fun nativeUuidStrings(): Array<String>
    private external fun nativeEnumValues(): IntArray
}

/** Outcome of [MotoProtocolCodec.pushDeviceFrame]. */
sealed interface MotoInboundResult {
    data class InProgress(val buffer: MotoInboundBuffer) : MotoInboundResult
    data object DuplicateFragment : MotoInboundResult
    data class DuplicateMessage(val sequence: Int) : MotoInboundResult
    data class Complete(val message: MotoInboundMessage) : MotoInboundResult
}

data class GoldenCheckResult(
    val passed: Boolean,
    val name: String,
    val detail: String,
)

private fun MotoInboundBuffer.toMessage(): MotoInboundMessage {
    val duplicateMessage = duplicate
    return when (kind) {
        MotoInboundBuffer.KIND_CONNECTION_STATUS -> MotoConnectionStatus(
            sequence = sequence,
            ackRequested = ackRequested,
            duplicate = duplicateMessage,
            role = role,
            state = state,
            minimumVersion = minimumVersion,
            maximumVersion = maximumVersion,
            capabilities = capabilities,
            sessionId = sessionId,
            maxFrameSize = maxFrameSize,
            heartbeatIntervalMs = heartbeatIntervalMs,
        )

        MotoInboundBuffer.KIND_HEARTBEAT -> MotoHeartbeat(
            sequence = sequence,
            ackRequested = ackRequested,
            duplicate = duplicateMessage,
            sessionId = sessionId,
            monotonicMs = heartbeatMonotonicMs,
            statusFlags = heartbeatStatusFlags,
        )

        MotoInboundBuffer.KIND_ACK -> MotoAck(
            sequence = sequence,
            ackRequested = ackRequested,
            duplicate = duplicateMessage,
            acknowledgedSequence = ackSequence,
            status = ackStatus,
            commandId = ackCommandId,
        )

        MotoInboundBuffer.KIND_DEVICE_COMMAND -> MotoDeviceCommand(
            sequence = sequence,
            ackRequested = ackRequested,
            duplicate = duplicateMessage,
            kind = commandKind,
            commandId = commandId,
            page = commandPage,
            x = commandX,
            y = commandY,
            eventTimeMs = commandEventTimeMs,
        )

        else -> MotoUnhandledMessage(
            sequence = sequence,
            ackRequested = ackRequested,
            duplicate = duplicateMessage,
            messageType = messageType,
        )
    }
}

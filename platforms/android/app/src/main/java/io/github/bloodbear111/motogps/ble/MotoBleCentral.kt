package io.github.bloodbear111.motogps.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.bloodbear111.motogps.protocol.AckStatus
import io.github.bloodbear111.motogps.protocol.BleHandshakeGate
import io.github.bloodbear111.motogps.protocol.BleSessionHeartbeatClock
import io.github.bloodbear111.motogps.protocol.BleWritePumpPolicy
import io.github.bloodbear111.motogps.protocol.ConnectionState
import io.github.bloodbear111.motogps.protocol.MotoAck
import io.github.bloodbear111.motogps.protocol.MotoConnectionStatus
import io.github.bloodbear111.motogps.protocol.MotoDeviceCommand
import io.github.bloodbear111.motogps.protocol.MotoHeartbeat
import io.github.bloodbear111.motogps.protocol.MotoInboundMessage
import io.github.bloodbear111.motogps.protocol.MotoInboundResult
import io.github.bloodbear111.motogps.protocol.MotoMapSceneInput
import io.github.bloodbear111.motogps.protocol.MotoNativeLibrary
import io.github.bloodbear111.motogps.protocol.MotoProtocolCodec
import io.github.bloodbear111.motogps.protocol.MotoProtocolException
import io.github.bloodbear111.motogps.protocol.MotoSnapshotInput
import io.github.bloodbear111.motogps.protocol.MotoUnhandledMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Owns one BLE session against the ESP32 navigation peripheral.
 *
 * Responsibilities kept here, and deliberately *not* in the Activity:
 * connection lifecycle, MTU negotiation, GATT serialisation, TX subscription,
 * the two-step v1 handshake, outbound fragmentation and pacing, application-level
 * ACK retries, inbound reassembly, command de-duplication and disconnect teardown
 * that clears the fragment queue.
 *
 * The v1 wire format itself stays in the shared C++ codec behind
 * [MotoProtocolCodec]; this class never assembles a frame by hand.
 *
 * Threading: GATT callbacks arrive on binder threads, so every codec access is
 * serialised on [codecLock] — the shared reassembler, sequence generator and
 * handshake gate are stateful.
 */
class MotoBleCentral(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val sessionIdFactory: () -> Int = DEFAULT_SESSION_ID_FACTORY,
) : AutoCloseable {

    /** Invoked for every device action that survived de-duplication. */
    var onDeviceCommand: ((MotoDeviceCommand) -> Unit)? = null

    /** Invoked when the protocol link goes stale and a fresh state is needed. */
    var onLinkStale: (() -> Unit)? = null

    private val codecLock = Any()
    private val queue = GattOperationQueue(scope)

    private val _state =
        MutableStateFlow<MotoBleConnectionState>(MotoBleConnectionState.Idle)
    val state: StateFlow<MotoBleConnectionState> = _state.asStateFlow()

    private val _frameSize =
        MutableStateFlow(MotoProtocolCodec.DEFAULT_MAXIMUM_FRAME_SIZE)
    val frameSize: StateFlow<Int> = _frameSize.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var codec: MotoProtocolCodec? = null

    /** Non-null when the shared native library could not be loaded or allocated. */
    var nativeError: String? = null
        private set

    private var handshake: BleHandshakeGate? = null
    private val heartbeatClock = BleSessionHeartbeatClock()

    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null

    private val writeFifo = ArrayDeque<ByteArray>()
    private var pumping = false
    private var lastWriteMs = 0L
    private var protocolReady = false
    private var lastValidFrameMs = 0L

    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var ackJob: Job? = null

    private var pendingAck: PendingAck? = null
    private val completedCommandIds = LinkedHashSet<Int>()

    private class PendingAck(
        val sequence: Int,
        val frames: Array<ByteArray>,
        var attempts: Int,
    )

    init {
        // Created up-front at the conservative default so the handshake can run
        // even if the stack never reports a larger ATT MTU.
        attachCodec(MotoProtocolCodec.DEFAULT_MAXIMUM_FRAME_SIZE)
    }

    // -----------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
    fun connect(device: BluetoothDevice) {
        disconnect("replaced by a new connection")
        if (codec == null) {
            _state.value = MotoBleConnectionState.Failed(
                device.address,
                nativeError ?: "the shared protocol library is unavailable",
            )
            return
        }
        queue.start()
        _state.value = MotoBleConnectionState.Connecting(
            name = runCatching { device.name }.getOrNull(),
            address = device.address,
        )
        // minSdk is 26, so the transport-aware overload is always available;
        // there is no legacy branch to keep untested.
        val connection = runCatching {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()

        if (connection == null) {
            _state.value = MotoBleConnectionState.Failed(
                device.address,
                "connectGatt returned null",
            )
            return
        }
        gatt = connection
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
    fun disconnect(reason: String) {
        val address = gatt?.device?.address
        teardownSession()
        gatt?.let { connection ->
            runCatching { connection.disconnect() }
            runCatching { connection.close() }
        }
        gatt = null
        _state.value = MotoBleConnectionState.Disconnected(address, reason)
    }

    // -----------------------------------------------------------------------
    // Outbound business messages
    // -----------------------------------------------------------------------

    /** Display snapshot. Snapshots never request an application ACK. */
    fun sendNavigationSnapshot(snapshot: MotoSnapshotInput): Boolean =
        enqueueEncoded { active -> active.encodeNavigationSnapshot(snapshot) }

    /** Map scene. Always requests an application ACK (spec section 6.8). */
    fun sendMapScene(scene: MotoMapSceneInput): Boolean =
        enqueueEncoded(requestAck = true) { active -> active.encodeMapScene(scene) }

    /**
     * Bounded heading-up route window. Returns false when there is nothing to
     * commit yet, matching the iOS bridge's empty result.
     */
    fun sendRouteGeometry(
        routeId: String,
        routeGeneration: Int,
        originLatitudeE6: Int,
        originLongitudeE6: Int,
        latitudesE6: IntArray,
        longitudesE6: IntArray,
        pointCount: Int,
    ): Boolean = enqueueEncoded { active ->
        active.encodeRouteGeometry(
            routeId,
            routeGeneration,
            originLatitudeE6,
            originLongitudeE6,
            latitudesE6,
            longitudesE6,
            pointCount,
        )
    }

    private fun enqueueEncoded(
        requestAck: Boolean = false,
        encode: (MotoProtocolCodec) -> Array<ByteArray>,
    ): Boolean {
        val active = codec ?: return false
        if (!protocolReady) return false

        val frames = try {
            synchronized(codecLock) { encode(active) }
        } catch (error: MotoProtocolException) {
            Log.w(TAG, "outbound payload rejected: ${error.message}")
            return false
        }
        if (frames.isEmpty()) return false

        if (requestAck) {
            if (pendingAck != null) {
                // The firmware accepts one acknowledged message at a time, so
                // overlapping a second one would be dropped on the device.
                Log.w(TAG, "an acknowledged message is already pending")
                return false
            }
            val sequence = synchronized(codecLock) { active.lastEncodedSequence }
            pendingAck = PendingAck(sequence, frames, attempts = 0)
            scheduleAckTimer()
        }
        return enqueueFrames(frames)
    }

    fun enqueueFrames(frames: Array<ByteArray>): Boolean {
        if (frames.isEmpty()) return false
        synchronized(writeFifo) { frames.forEach { writeFifo.addLast(it) } }
        pump()
        return true
    }

    // -----------------------------------------------------------------------
    // GATT callbacks
    // -----------------------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
        override fun onConnectionStateChange(
            connection: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            val address = connection.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "connection state status=$status newState=$newState")
                onTransportLost(address, "GATT connection failed (status $status)")
                runCatching { connection.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = MotoBleConnectionState.DiscoveringServices(address)
                    queue.enqueue { signal ->
                        val started = runCatching { connection.discoverServices() }
                            .getOrDefault(false)
                        if (!started) {
                            signal.complete(
                                GattOperationQueue.Outcome.Failure(
                                    "discoverServices was refused",
                                ),
                            )
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED ->
                    onTransportLost(address, "disconnected by peer or stack")

                else -> Unit
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            val address = connection.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onTransportLost(address, "service discovery failed (status $status)")
                return
            }
            val service = connection.getService(MotoBleUuids.service)
            if (service == null) {
                onTransportLost(
                    address,
                    "navigation service ${MotoBleUuids.service} not found on this device",
                )
                return
            }
            rx = service.getCharacteristic(MotoBleUuids.phoneToDevice)
            tx = service.getCharacteristic(MotoBleUuids.deviceToPhone)
            if (rx == null || tx == null) {
                onTransportLost(
                    address,
                    "RX/TX characteristics missing; this firmware build is not v1 compatible",
                )
                return
            }

            // MTU first: the negotiated value bounds every later write, including
            // the handshake.
            queue.enqueue { signal ->
                val requested = runCatching { connection.requestMtu(REQUESTED_ATT_MTU) }
                    .getOrDefault(false)
                if (!requested) {
                    // Not fatal: the stack keeps ATT MTU 23 and the codec stays at
                    // its conservative 20-byte default.
                    signal.complete(GattOperationQueue.Outcome.Success)
                }
            }
            queue.enqueue { signal -> subscribeTx(signal) }
            queue.enqueue { signal ->
                startHandshake(address)
                signal.complete(GattOperationQueue.Outcome.Success)
            }
        }

        override fun onMtuChanged(connection: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = MotoProtocolCodec.frameSizeForAttMtu(mtu)
                _frameSize.value = value
                synchronized(codecLock) { codec?.setMaximumFrameSize(value) }
            }
            // requestMtu does not produce a characteristic callback, so the
            // queued request is released here.
            queue.enqueue { it.complete(GattOperationQueue.Outcome.Success) }
        }

        override fun onDescriptorWrite(
            connection: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            queue.enqueue { signal ->
                signal.complete(
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        GattOperationQueue.Outcome.Success
                    } else {
                        GattOperationQueue.Outcome.Failure("CCCD write failed", status)
                    },
                )
            }
        }

        override fun onCharacteristicWrite(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // A failed write is not an application ACK. Drop the remainder of
                // this message rather than interleaving the next one into it.
                Log.w(TAG, "characteristic write failed status=$status")
                synchronized(writeFifo) { writeFifo.clear() }
            }
            pumping = false
            pump()
        }

        // Required for platforms that still dispatch the two-argument callback.
        // On API 33+ the framework calls the ByteArray overload instead, so the
        // value is never processed twice.
        @Deprecated("Fires on API < 33; the ByteArray overload is used on API 33+")
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleNotification(value)
        }

        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }
    }

    // -----------------------------------------------------------------------
    // Handshake, heartbeat, teardown
    // -----------------------------------------------------------------------

    private fun startHandshake(address: String) {
        val sessionId = sessionIdFactory()
        beginSession(sessionId)
        _state.value = MotoBleConnectionState.Handshaking(
            address = address,
            stage = "awaiting initial device Ready",
            maximumFrameSize = _frameSize.value,
        )
        val frames = synchronized(codecLock) { codec?.encodePhoneStarting(sessionId) }
            ?: return
        enqueueFrames(frames)
    }

    private fun beginSession(sessionId: Int) {
        val gate = BleHandshakeGate(_frameSize.value)
        gate.begin(sessionId)
        handshake = gate
        protocolReady = false
        heartbeatClock.begin(sessionId, clock())
        lastValidFrameMs = clock()
        completedCommandIds.clear()
        startHeartbeatLoop()
        startWatchdog()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                val gate = handshake ?: break
                val interval = gate.negotiatedHeartbeatIntervalMs
                    .takeIf { it >= BleHandshakeGate.MINIMUM_HEARTBEAT_INTERVAL_MS }
                    ?: DEFAULT_HEARTBEAT_INTERVAL_MS
                delay(interval.toLong())
                if (!protocolReady || pendingAck != null) continue
                val sessionId = gate.sessionId
                val elapsed = heartbeatClock.elapsedMs(sessionId, clock()) ?: continue
                val frames = synchronized(codecLock) {
                    codec?.encodeHeartbeat(sessionId, elapsed.toLong() and 0xFFFFFFFFL)
                } ?: continue
                enqueueFrames(frames)
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_POLL_MS)
                val gate = handshake ?: break
                if (!protocolReady) continue
                val silentMs = clock() - lastValidFrameMs
                if (silentMs > gate.deviceLivenessTimeoutMs) {
                    _state.value = MotoBleConnectionState.Degraded(
                        address = gatt?.device?.address.orEmpty(),
                        reason = "no CRC-valid frame for ${silentMs}ms",
                    )
                    // The firmware keeps the physical link and only marks
                    // navigation stale, so the phone mirrors that instead of
                    // dropping the connection.
                    lastValidFrameMs = clock()
                    onLinkStale?.invoke()
                }
            }
        }
    }

    private fun scheduleAckTimer() {
        ackJob?.cancel()
        ackJob = scope.launch {
            val pending = pendingAck ?: return@launch
            delay(ACK_TIMEOUT_MS)
            val current = pendingAck ?: return@launch
            if (current.sequence != pending.sequence) return@launch
            if (current.attempts >= MAX_ACK_RETRIES) {
                pendingAck = null
                _state.value = MotoBleConnectionState.Degraded(
                    address = gatt?.device?.address.orEmpty(),
                    reason = "application ACK missing after ${MAX_ACK_RETRIES + 1} attempts",
                )
                return@launch
            }
            // Spec section 4.3: resend the same sequence with identical fragments.
            current.attempts += 1
            enqueueFrames(current.frames)
            scheduleAckTimer()
        }
    }

    /** Drops per-session state. Outbound sequence numbers are intentionally kept. */
    private fun teardownSession() {
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        ackJob?.cancel()
        heartbeatJob = null
        watchdogJob = null
        ackJob = null

        protocolReady = false
        pendingAck = null
        completedCommandIds.clear()
        synchronized(writeFifo) { writeFifo.clear() }
        synchronized(codecLock) { codec?.resetInboundState() }
        handshake?.reset()
        handshake = null
        heartbeatClock.reset()
        queue.failAll("session torn down")
    }

    private fun onTransportLost(address: String, reason: String) {
        teardownSession()
        closeGattQuietly(gatt)
        gatt = null
        _state.value = MotoBleConnectionState.Disconnected(address, reason)
    }

    /**
     * `BluetoothGatt.close()` requires BLUETOOTH_CONNECT on API 31+. Losing the
     * permission mid-session must not crash teardown, so the SecurityException is
     * handled here rather than propagated.
     */
    @SuppressLint("MissingPermission")
    private fun closeGattQuietly(connection: BluetoothGatt?) {
        try {
            connection?.close()
        } catch (error: SecurityException) {
            Log.w(TAG, "close() refused without BLUETOOTH_CONNECT: ${error.message}")
        }
    }

    // -----------------------------------------------------------------------
    // Inbound handling
    // -----------------------------------------------------------------------

    private fun handleNotification(value: ByteArray) {
        val active = codec ?: return
        val result = try {
            synchronized(codecLock) { active.pushDeviceFrame(value, clock()) }
        } catch (error: MotoProtocolException) {
            Log.w(TAG, "dropping device frame: ${error.message}")
            return
        }

        // Any CRC-valid frame proves liveness, including partial fragments and
        // duplicate messages.
        lastValidFrameMs = clock()

        when (result) {
            is MotoInboundResult.InProgress,
            MotoInboundResult.DuplicateFragment,
            is MotoInboundResult.DuplicateMessage,
            -> Unit

            is MotoInboundResult.Complete -> handleInboundMessage(result.message)
        }
    }

    private fun handleInboundMessage(message: MotoInboundMessage) {
        when (message) {
            is MotoConnectionStatus -> handleConnectionStatus(message)
            is MotoHeartbeat -> handleHeartbeat(message)
            is MotoAck -> handleAck(message.acknowledgedSequence, message.status)
            is MotoDeviceCommand -> handleDeviceCommand(message)
            is MotoUnhandledMessage ->
                Log.i(TAG, "ignoring device message type 0x%02X".format(message.messageType))
        }
    }

    private fun handleHeartbeat(message: MotoHeartbeat) {
        val gate = handshake ?: return
        if (message.sessionId != gate.sessionId) {
            restartHandshakeForDeviceSession(message.sessionId)
        }
    }

    private fun handleConnectionStatus(status: MotoConnectionStatus) {
        val gate = handshake ?: return
        when (status.state) {
            ConnectionState.Closing.code -> {
                onTransportLost(
                    gatt?.device?.address.orEmpty(),
                    "device closed the protocol session",
                )
                return
            }

            ConnectionState.Degraded.code -> {
                _state.value = MotoBleConnectionState.Degraded(
                    address = gatt?.device?.address.orEmpty(),
                    reason = "device reported a degraded session",
                )
                return
            }

            else -> Unit
        }

        if (status.sessionId != gate.sessionId) {
            restartHandshakeForDeviceSession(status.sessionId)
            return
        }

        val transition = try {
            gate.acceptDeviceReady(BleHandshakeGate.from(status))
        } catch (rejected: BleHandshakeGate.HandshakeRejected) {
            Log.w(TAG, "handshake rejected: ${rejected.message}")
            _state.value = MotoBleConnectionState.Failed(
                gatt?.device?.address,
                rejected.message ?: "handshake rejected",
            )
            return
        }

        when (transition) {
            is BleHandshakeGate.Transition.SendPhoneReady -> {
                applyFrameSize(transition.maximumFrameSize)
                _state.value = MotoBleConnectionState.Handshaking(
                    address = gatt?.device?.address.orEmpty(),
                    stage = "awaiting final device Ready",
                    maximumFrameSize = transition.maximumFrameSize,
                )
                val frames = synchronized(codecLock) {
                    codec?.encodePhoneReady(gate.sessionId)
                } ?: return
                enqueueFrames(frames)
            }

            is BleHandshakeGate.Transition.ProtocolReady -> {
                applyFrameSize(transition.maximumFrameSize)
                protocolReady = true
                _state.value = MotoBleConnectionState.ProtocolReady(
                    address = gatt?.device?.address.orEmpty(),
                    maximumFrameSize = transition.maximumFrameSize,
                    heartbeatIntervalMs = gate.negotiatedHeartbeatIntervalMs,
                    capabilities = status.capabilities,
                )
            }
        }
    }

    private fun applyFrameSize(value: Int) {
        _frameSize.value = value
        synchronized(codecLock) { codec?.setMaximumFrameSize(value) }
    }

    /**
     * A device-side session change cancels every per-session structure: the
     * route window, the command de-duplication table and any partial fragments.
     */
    private fun restartHandshakeForDeviceSession(deviceSessionId: Int) {
        Log.i(TAG, "device opened session 0x%08X; restarting handshake".format(deviceSessionId))
        val address = gatt?.device?.address.orEmpty()
        teardownSession()
        beginSession(deviceSessionId)
        _state.value = MotoBleConnectionState.Handshaking(
            address = address,
            stage = "restarted for a new device session",
            maximumFrameSize = _frameSize.value,
        )
        val frames = synchronized(codecLock) { codec?.encodePhoneStarting(deviceSessionId) }
            ?: return
        enqueueFrames(frames)
    }

    /** @return true when this ACK matched the outstanding acknowledged message. */
    private fun handleAck(acknowledgedSequence: Int, status: Int): Boolean {
        val pending = pendingAck ?: return false
        if (pending.sequence != acknowledgedSequence) return false
        pendingAck = null
        ackJob?.cancel()
        if (status != AckStatus.Ok.code && status != AckStatus.Duplicate.code) {
            Log.w(TAG, "application ACK reported status $status")
        }
        return true
    }

    private fun handleDeviceCommand(command: MotoDeviceCommand) {
        if (command.duplicate) return
        val kind = command.kindOrNull()
        if (kind == null) {
            if (command.ackRequested) acknowledge(command, AckStatus.Unsupported)
            return
        }

        // De-duplication key is (session_id, command_id): a device retry is
        // acknowledged again but must never apply a second side effect.
        val firstTime = completedCommandIds.add(command.commandId)
        if (firstTime) {
            onDeviceCommand?.invoke(command)
        }
        if (command.ackRequested) {
            acknowledge(
                command = command,
                status = if (firstTime) AckStatus.Ok else AckStatus.Duplicate,
            )
        }
        while (completedCommandIds.size > COMMAND_DEDUP_CACHE_LIMIT) {
            val oldest = completedCommandIds.first()
            completedCommandIds.remove(oldest)
        }
    }

    private fun acknowledge(command: MotoDeviceCommand, status: AckStatus) {
        val frames = synchronized(codecLock) {
            codec?.encodeAck(command.sequence, status, command.commandId)
        } ?: return
        enqueueFrames(frames)
    }

    // -----------------------------------------------------------------------
    // Write pump
    // -----------------------------------------------------------------------

    private fun pump() {
        if (pumping) return
        val frame = synchronized(writeFifo) {
            if (writeFifo.isEmpty()) return
            writeFifo.first()
        }
        val action = BleWritePumpPolicy.action(
            pendingFrameCount = 1,
            canSend = !queue.isBusy,
            nowMs = clock(),
            lastSendMs = lastWriteMs,
            pacingMs = WRITE_PACING_MS,
        )
        when (action) {
            BleWritePumpPolicy.Action.Idle -> Unit

            is BleWritePumpPolicy.Action.Wait -> scope.launch {
                delay(action.milliseconds)
                pump()
            }

            BleWritePumpPolicy.Action.SendOne -> {
                pumping = true
                if (!writeFrame(frame)) {
                    pumping = false
                    synchronized(writeFifo) { writeFifo.clear() }
                } else {
                    lastWriteMs = clock()
                    synchronized(writeFifo) {
                        if (writeFifo.isNotEmpty()) writeFifo.removeFirst()
                    }
                }
            }
        }
    }

    private fun writeFrame(value: ByteArray): Boolean {
        // SecurityException is caught below: a permission revoked while the app
        // is running must surface as a failed write, not a crash.
        val connection = gatt ?: return false
        val characteristic = rx ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                connection.writeCharacteristic(characteristic)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "write refused: ${error.message}")
            false
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
    private fun subscribeTx(signal: CompletableDeferred<GattOperationQueue.Outcome>) {
        val connection = gatt
        val characteristic = tx
        if (connection == null || characteristic == null) {
            signal.complete(
                GattOperationQueue.Outcome.Failure("TX characteristic unavailable"),
            )
            return
        }
        val enabled = runCatching {
            connection.setCharacteristicNotification(characteristic, true)
        }.getOrDefault(false)
        if (!enabled) {
            signal.complete(
                GattOperationQueue.Outcome.Failure("TX notification enable was refused"),
            )
            return
        }

        val descriptor = characteristic.getDescriptor(MotoBleUuids.cccd)
        if (descriptor == null) {
            signal.complete(GattOperationQueue.Outcome.Failure("TX CCCD not found"))
            return
        }
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                connection.writeDescriptor(descriptor)
            }
        }.getOrDefault(false)
        if (!started) {
            signal.complete(GattOperationQueue.Outcome.Failure("CCCD write was refused"))
        }
    }

    /** Creates the per-connection codec. Exposed for tests. */
    internal fun attachCodec(maximumFrameSize: Int) {
        synchronized(codecLock) {
            codec?.close()
            codec = try {
                MotoNativeLibrary.requireLoaded()
                MotoProtocolCodec(maximumFrameSize).also {
                    nativeError = null
                    _frameSize.value = maximumFrameSize
                }
            } catch (error: Throwable) {
                // A missing libmoto_mobile leaves the transport inert but keeps
                // the app able to explain why instead of crashing at startup.
                nativeError = error.message ?: error::class.java.simpleName
                null
            }
        }
    }

    /** True once both Ready messages matched and business payloads may flow. */
    val isProtocolReady: Boolean
        get() = protocolReady

    override fun close() {
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        ackJob?.cancel()
        queue.shutdown()
        synchronized(codecLock) {
            codec?.close()
            codec = null
        }
        closeGattQuietly(gatt)
        gatt = null
    }

    companion object {
        private const val TAG = "MotoBleCentral"

        /** Preferred ATT MTU; the effective value comes from `onMtuChanged`. */
        const val REQUESTED_ATT_MTU = 517

        /** Minimum gap between two characteristic writes. */
        const val WRITE_PACING_MS = 15L

        /** Spec section 4.3: 750 ms timeout, at most 2 retries. */
        const val ACK_TIMEOUT_MS = 750L
        const val MAX_ACK_RETRIES = 2

        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 1_000
        const val WATCHDOG_POLL_MS = 500L
        const val COMMAND_DEDUP_CACHE_LIMIT = 256

        private val DEFAULT_SESSION_ID_FACTORY: () -> Int = {
            val value = SecureRandom().nextInt()
            if (value == 0) 1 else value
        }
    }
}

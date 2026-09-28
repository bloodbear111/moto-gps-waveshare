package io.github.bloodbear111.motogps.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serialises GATT requests.
 *
 * Android allows exactly one outstanding GATT operation per connection; a second
 * call returns `false` and is dropped silently. Every request therefore goes
 * through this queue and completes only when the matching `onX` callback fires,
 * never by assuming success from the return value.
 *
 * Frames belonging to one logical message are enqueued in order and the queue is
 * drained strictly FIFO, so fragments of different messages can never interleave
 * even under back-pressure or a mid-message disconnect.
 */
class GattOperationQueue(
    private val scope: CoroutineScope,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** Result of one queued GATT request. */
    sealed interface Outcome {
        data object Success : Outcome
        data class Failure(val message: String, val status: Int? = null) : Outcome
        data object TimedOut : Outcome
    }

    private class Pending(
        val timeoutMs: Long,
        val start: (CompletableDeferred<Outcome>) -> Unit,
    )

    private val channel = Channel<Pending>(Channel.UNLIMITED)
    private val queuedCount = AtomicInteger(0)
    private var worker: Job? = null

    @Volatile
    private var inFlight: CompletableDeferred<Outcome>? = null

    /** True while a request is outstanding; the write pump must wait. */
    val isBusy: Boolean
        get() = inFlight != null

    /** Requests accepted but not yet completed or timed out. */
    val outstandingCount: Int
        get() = queuedCount.get()

    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (pending in channel) {
                val signal = CompletableDeferred<Outcome>()
                inFlight = signal
                try {
                    pending.start(signal)
                    withTimeoutOrNull(pending.timeoutMs) { signal.await() }
                        ?: signal.complete(Outcome.TimedOut)
                } finally {
                    inFlight = null
                    queuedCount.decrementAndGet()
                }
            }
        }
    }

    /**
     * Enqueues [body]. [body] receives the completer and must complete it from
     * the GATT callback that corresponds to this request.
     */
    fun enqueue(
        timeoutMs: Long = defaultTimeoutMs,
        body: (CompletableDeferred<Outcome>) -> Unit,
    ): Boolean {
        queuedCount.incrementAndGet()
        val result = channel.trySend(Pending(timeoutMs, body))
        if (result.isFailure) {
            queuedCount.decrementAndGet()
        }
        return result.isSuccess
    }

    /**
     * Fails the outstanding request and drops everything queued. Used on
     * disconnect: a queued fragment must never be written into the next session.
     */
    fun failAll(reason: String, status: Int? = null) {
        inFlight?.complete(Outcome.Failure(reason, status))
        inFlight = null
        var remaining = 0
        while (true) {
            val result = channel.tryReceive()
            if (result.isFailure) break
            remaining++
        }
        if (remaining > 0) queuedCount.addAndGet(-remaining)
    }

    fun shutdown(reason: String = "GATT queue shut down") {
        failAll(reason)
        channel.close()
        worker = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

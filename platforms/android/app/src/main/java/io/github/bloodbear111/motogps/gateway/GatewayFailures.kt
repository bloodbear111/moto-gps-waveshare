package io.github.bloodbear111.motogps.gateway

/**
 * Why a gateway call did not produce usable data.
 *
 * The navigation session maps these onto protocol state; none of them may be
 * silently turned into a demo route or a fallback position.
 */
sealed class GatewayFailure(message: String) : Exception(message) {

    /** No gateway address is configured. The UI must ask the user to set one. */
    data object NotConfigured :
        GatewayFailure("gateway address is not configured")

    /** DNS, TLS, connection or read timeout. */
    data class Transport(val reason: String) :
        GatewayFailure("gateway unreachable: $reason")

    /** A structured error envelope from the gateway (HTTP 4xx/5xx). */
    data class Service(
        val httpStatus: Int,
        val code: String,
        val detail: String,
        val retryable: Boolean,
        val retryAfterSeconds: Int?,
    ) : GatewayFailure("gateway error $code (HTTP $httpStatus): $detail")

    /** A 2xx body that does not match the documented schema. */
    data class Protocol(val reason: String) :
        GatewayFailure("gateway response rejected: $reason")

    /**
     * The response carried a different `request_id` than the request that is
     * still active. The gateway echoes the id precisely so stale replies can be
     * dropped instead of replacing a newer route.
     */
    data class Stale(val expectedRequestId: Int, val receivedRequestId: Int?) :
        GatewayFailure("stale response for request $receivedRequestId, active $expectedRequestId")
}

/** Result of a gateway call: either data or a typed failure. */
sealed interface GatewayResult<out T> {
    data class Success<T>(val value: T) : GatewayResult<T>
    data class Failure(val failure: GatewayFailure) : GatewayResult<Nothing>
}

inline fun <T, R> GatewayResult<T>.map(transform: (T) -> R): GatewayResult<R> = when (this) {
    is GatewayResult.Success -> GatewayResult.Success(transform(value))
    is GatewayResult.Failure -> this
}

fun <T> GatewayResult<T>.getOrNull(): T? =
    (this as? GatewayResult.Success<T>)?.value

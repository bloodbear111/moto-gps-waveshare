package io.github.bloodbear111.motogps.protocol

/**
 * Raised when the shared C++ codec rejects a frame or payload. [code] is the
 * `moto::ble::Error` value so callers can distinguish e.g. `CrcMismatch` from
 * `UnsupportedVersion` without parsing the message.
 */
class MotoProtocolException(
    message: String,
    val code: Int,
    val offset: Int,
) : Exception(message)

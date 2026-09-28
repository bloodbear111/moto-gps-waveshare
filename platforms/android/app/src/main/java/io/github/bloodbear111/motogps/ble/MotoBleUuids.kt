package io.github.bloodbear111.motogps.ble

import io.github.bloodbear111.motogps.protocol.MotoNativeLibrary
import io.github.bloodbear111.motogps.protocol.MotoProtocolCodec
import java.util.UUID

/**
 * GATT identifiers for the v1 contract.
 *
 * The authoritative values live in `moto::ble::kServiceUuid` and friends in
 * `shared/ble_protocol`. They are read from the native library at first use; the
 * literals below are the documented fallback used by JVM unit tests, which have
 * no native library. `MotoBleUuidsTest` and the instrumented suite assert the
 * two agree.
 */
object MotoBleUuids {

    const val SERVICE = "7e57a000-b50c-4b6a-9c57-40a54e8e1000"
    const val PHONE_TO_DEVICE = "7e57a001-b50c-4b6a-9c57-40a54e8e1000"
    const val DEVICE_TO_PHONE = "7e57a002-b50c-4b6a-9c57-40a54e8e1000"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    val service: UUID = UUID.fromString(SERVICE)
    val phoneToDevice: UUID = UUID.fromString(PHONE_TO_DEVICE)
    val deviceToPhone: UUID = UUID.fromString(DEVICE_TO_PHONE)
    val cccd: UUID = UUID.fromString(CCCD)

    /** Reads the UUIDs from the shared header. Returns null without the library. */
    fun readFromNative(): List<UUID>? {
        if (!MotoNativeLibrary.isLoaded) return null
        return try {
            MotoProtocolCodec().use { codec ->
                codec.gattUuids().map(UUID::fromString)
            }
        } catch (error: Throwable) {
            null
        }
    }
}

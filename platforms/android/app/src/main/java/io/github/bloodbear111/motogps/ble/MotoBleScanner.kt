package io.github.bloodbear111.motogps.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.annotation.SuppressLint
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A discovered peripheral advertising the MOTO GPS navigation service. */
data class MotoBleDiscovery(
    val name: String?,
    val address: String,
    val rssi: Int,
    val seenAtMs: Long,
    val device: BluetoothDevice,
)

/**
 * BLE scanner filtered by the v1 service UUID.
 *
 * Filtering by the service UUID is part of the contract: only hardware running
 * the MOTO GPS firmware advertises it, so the phone must not present an
 * arbitrary device list. Scanning uses `neverForLocation`, which means no
 * location permission is asked for discovery — navigation positioning requests
 * location separately, and neither permission substitutes for the other.
 */
class MotoBleScanner(private val context: Context) {

    /**
     * Emits one entry per advertisement while the returned flow is collected.
     * Collection is cancelled when the caller leaves the scan screen; the
     * callback is always unregistered via `awaitClose`.
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN])
    fun scan(): Flow<MotoBleDiscovery> = callbackFlow {
        val scanner = BlePermissions.adapter(context)?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanning is unavailable"))
            return@callbackFlow
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MotoBleUuids.service))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                trySend(
                    MotoBleDiscovery(
                        name = readableName(device),
                        address = device.address,
                        rssi = result.rssi,
                        seenAtMs = System.currentTimeMillis(),
                        device = device,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed with code $errorCode"))
            }
        }

        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * Reading `BluetoothDevice.name` needs BLUETOOTH_CONNECT on API 31+. The
     * caller only reaches this method through [scan], and a device that cannot
     * expose its name is still usable — the address is the connection handle.
     */
    @SuppressLint("MissingPermission")
    private fun readableName(device: BluetoothDevice): String? {
        if (!BlePermissions.hasScanPermission(context)) return null
        return runCatching { device.name }.getOrNull()
    }
}

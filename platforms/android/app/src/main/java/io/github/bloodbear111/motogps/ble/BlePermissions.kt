package io.github.bloodbear111.motogps.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Bluetooth and location permission handling.
 *
 * Two permission families are deliberately kept apart:
 * * **BLE scanning/connecting** needs `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on
 *   API 31+, and `ACCESS_FINE_LOCATION` below that.
 * * **Navigation positioning** needs location permission independently. Scanning
 *   is declared with `neverForLocation`, which is a promise that scan results are
 *   not used to derive position — it does *not* remove the need for location
 *   permission while navigating. Losing that distinction is the classic way an
 *   Android port silently stops updating the position.
 */
object BlePermissions {

    val scanPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Permissions required to read GNSS while navigating. */
    val navigationLocationPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    val notificationsPermission: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    fun hasScanPermission(context: Context): Boolean =
        scanPermissions.all { granted(context, it) }

    fun hasNavigationLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * True when the user granted only an approximate location. Navigation cannot
     * run on a blurred fix, so the UI must ask for precise access instead of
     * pretending a coarse fix is usable.
     */
    fun hasApproximateOnlyLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * Raw grant state for the on-screen bring-up diagnostics. "Granted" and
     * "granted precisely" are different answers: an approximate grant is
     * delivered as a fix and must be refused by name rather than shown as a
     * position the rider could act on.
     */
    fun describeLocationPermission(context: Context): String =
        "fine=" + granted(context, Manifest.permission.ACCESS_FINE_LOCATION) +
            " coarse=" + granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java)
        return manager?.adapter?.isEnabled == true
    }

    fun isBluetoothLeSupported(context: Context): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java)
        return manager?.adapter?.isMultipleAdvertisementSupported != false &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}

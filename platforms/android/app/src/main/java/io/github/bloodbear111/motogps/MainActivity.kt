package io.github.bloodbear111.motogps

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bloodbear111.motogps.ble.BlePermissions
import io.github.bloodbear111.motogps.ui.ConnectionViewModel
import io.github.bloodbear111.motogps.ui.MotoGpsApp

/**
 * Single Activity host.
 *
 * It owns no connection state: permissions are requested here because the
 * platform requires an Activity, and everything else lives in
 * [ConnectionViewModel] so a configuration change cannot drop the BLE session.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ConnectionViewModel by viewModels()

    private var bluetoothPermissionGranted by mutableStateOf(false)
    private var locationPermissionGranted by mutableStateOf(false)
    private var approximateLocationOnly by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)
    private var bluetoothLeSupported by mutableStateOf(true)

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissionState() }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionState()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color(0xFF101010),
                ),
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val connectionState by
                        viewModel.connectionState.collectAsStateWithLifecycle()
                    val frameSize by viewModel.frameSize.collectAsStateWithLifecycle()
                    val discoveries by viewModel.discoveries.collectAsStateWithLifecycle()
                    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
                    val scanError by viewModel.scanError.collectAsStateWithLifecycle()
                    val selfTest by viewModel.selfTest.collectAsStateWithLifecycle()
                    val gateway by viewModel.gatewayAddress.collectAsStateWithLifecycle()
                    val places by viewModel.places.collectAsStateWithLifecycle()
                    val searching by viewModel.searching.collectAsStateWithLifecycle()
                    val destinationError by
                        viewModel.destinationError.collectAsStateWithLifecycle()
                    val navigation by viewModel.navigation.collectAsStateWithLifecycle()

                    MotoGpsApp(
                        connectionState = connectionState,
                        frameSize = frameSize,
                        discoveries = discoveries,
                        scanning = scanning,
                        scanError = scanError,
                        selfTest = selfTest,
                        gatewayAddress = gateway,
                        places = places,
                        searching = searching,
                        destinationError = destinationError,
                        navigation = navigation,
                        hasBluetoothPermission = bluetoothPermissionGranted,
                        bluetoothEnabled = bluetoothEnabled,
                        bluetoothLeSupported = bluetoothLeSupported,
                        hasLocationPermission = locationPermissionGranted,
                        approximateLocationOnly = approximateLocationOnly,
                        onRequestBluetooth = {
                            bluetoothLauncher.launch(BlePermissions.scanPermissions)
                        },
                        onRequestLocation = {
                            locationLauncher.launch(
                                BlePermissions.navigationLocationPermissions,
                            )
                        },
                        onOpenBluetoothSettings = {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        onStartScan = viewModel::startScan,
                        onStopScan = viewModel::stopScan,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        onRunSelfTest = viewModel::runSelfTest,
                        onSaveGateway = viewModel::saveGatewayAddress,
                        onSearchDestination = viewModel::searchDestination,
                        // Navigation cannot plan anything without a precise
                        // fix, so ask for the permission here instead of leaving
                        // the rider to find it in Settings.
                        onStartNavigation = { place ->
                            if (locationPermissionGranted && !approximateLocationOnly) {
                                viewModel.startNavigation(place)
                            } else {
                                locationLauncher.launch(
                                    BlePermissions.navigationLocationPermissions,
                                )
                            }
                        },
                        onStopNavigation = viewModel::stopNavigation,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun refreshPermissionState() {
        bluetoothPermissionGranted = BlePermissions.hasScanPermission(this)
        locationPermissionGranted = BlePermissions.hasNavigationLocationPermission(this)
        approximateLocationOnly = BlePermissions.hasApproximateOnlyLocation(this)
        // Re-read on every resume so toggling Bluetooth in system settings is
        // reflected without restarting the app.
        bluetoothEnabled = BlePermissions.isBluetoothEnabled(this)
        bluetoothLeSupported = BlePermissions.isBluetoothLeSupported(this)
    }
}

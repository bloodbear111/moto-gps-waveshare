package io.github.bloodbear111.motogps.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.bloodbear111.motogps.R
import io.github.bloodbear111.motogps.ble.MotoBleConnectionState
import io.github.bloodbear111.motogps.ble.MotoBleDiscovery

/**
 * Stage-1 shell: connect to the round display and verify the shared protocol
 * codec. Destination search, route selection and map downloads arrive in later
 * stages and are not stubbed here — a tab that pretends to navigate would be
 * worse than no tab.
 */
@Composable
fun MotoGpsApp(
    connectionState: MotoBleConnectionState,
    frameSize: Int,
    discoveries: List<MotoBleDiscovery>,
    scanning: Boolean,
    scanError: String?,
    selfTest: SelfTestReport,
    gatewayAddress: String?,
    hasBluetoothPermission: Boolean,
    bluetoothEnabled: Boolean,
    bluetoothLeSupported: Boolean,
    hasLocationPermission: Boolean,
    approximateLocationOnly: Boolean,
    onRequestBluetooth: () -> Unit,
    onRequestLocation: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (MotoBleDiscovery) -> Unit,
    onDisconnect: () -> Unit,
    onRunSelfTest: () -> Unit,
    onSaveGateway: (String) -> String?,
) {
    var tab by remember { mutableIntStateOf(0) }
    val titleRes = listOf(
        R.string.tab_connect,
        R.string.tab_selftest,
        R.string.tab_settings,
    )
    val titles = titleRes.map { stringResource(it) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                imageVector = when (index) {
                                    0 -> Icons.Filled.Bluetooth
                                    1 -> Icons.AutoMirrored.Filled.FactCheck
                                    else -> Icons.Filled.Settings
                                },
                                contentDescription = title,
                            )
                        },
                        label = { Text(title) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (tab) {
            0 -> ConnectScreen(
                modifier = contentModifier,
                connectionState = connectionState,
                frameSize = frameSize,
                discoveries = discoveries,
                scanning = scanning,
                scanError = scanError,
                hasBluetoothPermission = hasBluetoothPermission,
                bluetoothEnabled = bluetoothEnabled,
                bluetoothLeSupported = bluetoothLeSupported,
                onRequestBluetooth = onRequestBluetooth,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
            )

            1 -> SelfTestScreen(
                modifier = contentModifier,
                report = selfTest,
                onRun = onRunSelfTest,
            )

            else -> SettingsScreen(
                modifier = contentModifier,
                gatewayAddress = gatewayAddress,
                hasLocationPermission = hasLocationPermission,
                approximateLocationOnly = approximateLocationOnly,
                onRequestLocation = onRequestLocation,
                onSaveGateway = onSaveGateway,
            )
        }
    }
}

package io.github.bloodbear111.motogps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bloodbear111.motogps.R
import io.github.bloodbear111.motogps.ble.MotoBleConnectionState
import io.github.bloodbear111.motogps.ble.MotoBleDiscovery

@Composable
fun ConnectScreen(
    modifier: Modifier = Modifier,
    connectionState: MotoBleConnectionState,
    frameSize: Int,
    discoveries: List<MotoBleDiscovery>,
    scanning: Boolean,
    scanError: String?,
    hasBluetoothPermission: Boolean,
    bluetoothEnabled: Boolean,
    bluetoothLeSupported: Boolean,
    onRequestBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (MotoBleDiscovery) -> Unit,
    onDisconnect: () -> Unit,
) {
    // The header (permission / scan buttons) and the discovered devices share one
    // scrollable list, so nothing can end up below the fold and unreachable.
    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionCard(
                    state = connectionState,
                    frameSize = frameSize,
                    onDisconnect = onDisconnect,
                )

                if (!bluetoothLeSupported) {
                    Text(
                        text = stringResource(R.string.bluetooth_unsupported),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (!hasBluetoothPermission) {
                    Text(
                        text = stringResource(R.string.bluetooth_permission_missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onRequestBluetooth) {
                        Text(stringResource(R.string.grant_bluetooth))
                    }
                } else if (!bluetoothEnabled) {
                    Text(
                        text = stringResource(R.string.bluetooth_off),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenBluetoothSettings) {
                        Text(stringResource(R.string.open_bluetooth_settings))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (scanning) {
                            OutlinedButton(onClick = onStopScan) {
                                Text(stringResource(R.string.scan_stop))
                            }
                        } else {
                            Button(onClick = onStartScan) {
                                Text(stringResource(R.string.scan_start))
                            }
                        }
                    }
                }

                scanError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (discoveries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.scan_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        items(discoveries, key = { it.address }) { discovery ->
            DiscoveryRow(discovery = discovery, onConnect = onConnect)
        }
    }
}

@Composable
private fun DiscoveryRow(
    discovery: MotoBleDiscovery,
    onConnect: (MotoBleDiscovery) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(discovery.name ?: stringResource(R.string.ble_unnamed_device))
                Text(
                    text = stringResource(
                        R.string.ble_device_detail,
                        discovery.address,
                        discovery.rssi,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { onConnect(discovery) }) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: MotoBleConnectionState,
    frameSize: Int,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.ble_status_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(describe(state))
            Text(
                text = stringResource(R.string.ble_frame_size, frameSize),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.ble_handshake_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            if (state !is MotoBleConnectionState.Idle) {
                OutlinedButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.disconnect))
                }
            }
        }
    }
}

@Composable
private fun describe(state: MotoBleConnectionState): String = when (state) {
    is MotoBleConnectionState.Idle -> stringResource(R.string.ble_idle)
    is MotoBleConnectionState.Scanning ->
        stringResource(R.string.ble_scanning_found, state.found)

    is MotoBleConnectionState.Connecting ->
        stringResource(R.string.ble_connecting, state.name ?: state.address)

    is MotoBleConnectionState.DiscoveringServices ->
        stringResource(R.string.ble_discovering_services)

    is MotoBleConnectionState.Handshaking ->
        stringResource(R.string.ble_handshaking, state.stage)

    is MotoBleConnectionState.ProtocolReady -> stringResource(
        R.string.ble_protocol_ready,
        state.maximumFrameSize,
        state.heartbeatIntervalMs,
        "%08X".format(state.capabilities),
    )

    is MotoBleConnectionState.Degraded ->
        stringResource(R.string.ble_degraded, state.reason)

    is MotoBleConnectionState.Disconnected ->
        stringResource(R.string.ble_disconnected, state.reason)

    is MotoBleConnectionState.Failed -> state.status?.let { status ->
        stringResource(R.string.ble_failed_status, state.reason, status)
    } ?: stringResource(R.string.ble_failed, state.reason)
}

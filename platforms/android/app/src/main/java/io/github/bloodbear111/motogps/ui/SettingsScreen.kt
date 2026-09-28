package io.github.bloodbear111.motogps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bloodbear111.motogps.R

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    gatewayAddress: String?,
    hasLocationPermission: Boolean,
    approximateLocationOnly: Boolean,
    onRequestLocation: () -> Unit,
    onSaveGateway: (String) -> String?,
) {
    var input by remember(gatewayAddress) { mutableStateOf(gatewayAddress.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.gateway_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.gateway_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        saved = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.gateway_hint)) },
                )
                Button(
                    onClick = {
                        val error = onSaveGateway(input)
                        message = error
                        saved = error == null
                    },
                ) { Text(stringResource(R.string.gateway_save)) }

                if (saved) {
                    Text(
                        text = stringResource(R.string.gateway_saved),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                message?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                if (gatewayAddress == null) {
                    Text(
                        text = stringResource(R.string.gateway_unset),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.location_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.location_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    !hasLocationPermission -> Text(
                        text = stringResource(R.string.permission_location_missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    approximateLocationOnly -> Text(
                        text = stringResource(R.string.permission_location_approximate),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> Text(stringResource(R.string.location_ok))
                }
                Button(onClick = onRequestLocation) {
                    Text(stringResource(R.string.request_location))
                }
            }
        }
    }
}

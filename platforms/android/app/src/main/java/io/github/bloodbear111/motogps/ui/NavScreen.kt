package io.github.bloodbear111.motogps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.bloodbear111.motogps.gateway.GatewayPlace
import io.github.bloodbear111.motogps.navigation.NavigationSession

/**
 * Destination search and navigation control.
 *
 * Every number here comes from the shared NavCore's snapshot; nothing is
 * estimated on the phone, so the screen and the round display always agree.
 */
@Composable
fun NavScreen(
    modifier: Modifier = Modifier,
    gatewayAddress: String?,
    gatewayConfigured: Boolean,
    places: List<GatewayPlace>,
    searching: Boolean,
    destinationError: String?,
    session: NavigationSession.Snapshot,
    hasPreciseLocation: Boolean,
    approximateLocationOnly: Boolean,
    locationEvents: List<String>,
    onSearch: (String) -> Unit,
    onStart: (GatewayPlace) -> Unit,
    onStop: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_title),
            style = MaterialTheme.typography.titleLarge,
        )

        if (!gatewayConfigured) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.nav_gateway_missing),
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.nav_gateway, gatewayAddress.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (session.active) {
            ActiveNavigationCard(
                session = session,
                hasPreciseLocation = hasPreciseLocation,
                approximateLocationOnly = approximateLocationOnly,
                locationEvents = locationEvents,
                onStop = onStop,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.nav_search_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            Button(
                onClick = { onSearch(query) },
                enabled = gatewayConfigured && !searching && query.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    text = stringResource(
                        if (searching) R.string.nav_searching else R.string.nav_search,
                    ),
                )
            }
        }

        destinationError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (places.isEmpty() && !searching && destinationError == null) {
            Text(
                text = stringResource(R.string.nav_no_results),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(places, key = { it.id }) { place ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth(0.65f)) {
                            Text(place.name)
                            Text(
                                text = place.displayArea.ifBlank { place.address },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = { onStart(place) }) {
                            Text(stringResource(R.string.nav_start))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveNavigationCard(
    session: NavigationSession.Snapshot,
    hasPreciseLocation: Boolean,
    approximateLocationOnly: Boolean,
    locationEvents: List<String>,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.nav_active_title),
                style = MaterialTheme.typography.titleMedium,
            )
            session.destinationName?.let {
                Text(stringResource(R.string.nav_destination, it))
            }
            Text(stringResource(R.string.nav_speed, session.speedKph))
            Text(
                stringResource(
                    R.string.nav_remaining,
                    (session.remainingDistanceM / 1000.0),
                    (session.remainingDurationS / 60),
                ),
            )
            if (session.nextInstruction.isNotBlank()) {
                Text(stringResource(R.string.nav_next, session.nextInstruction))
            }
            if (session.offRoute) {
                Text(
                    text = stringResource(R.string.nav_off_route),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (session.gnssStale || !session.hasFix) {
                // Say *why*, because the core plans no route at all until it has
                // a usable fix: the round screen then just shows "choose a
                // destination on the phone" and nothing else happens.
                val reason = when {
                    !hasPreciseLocation && approximateLocationOnly ->
                        R.string.nav_fix_coarse_only
                    !hasPreciseLocation -> R.string.nav_fix_no_permission
                    session.fixesAccepted == 0 -> R.string.nav_fix_none_yet
                    else -> R.string.nav_fix_not_usable
                }
                Text(
                    text = stringResource(reason, session.fixesAccepted),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(
                    R.string.nav_sent,
                    session.sentSnapshots,
                    session.sentRouteWindows,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            session.lastError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (locationEvents.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.nav_location_event),
                    style = MaterialTheme.typography.bodySmall,
                )
                locationEvents.forEach { event ->
                    Text(
                        text = event,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedButton(onClick = onStop) {
                Text(stringResource(R.string.nav_stop))
            }
        }
    }
}

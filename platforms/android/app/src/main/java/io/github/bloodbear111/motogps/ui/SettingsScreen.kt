package io.github.bloodbear111.motogps.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bloodbear111.motogps.BuildConfig
import io.github.bloodbear111.motogps.R
import io.github.bloodbear111.motogps.navigation.AmapLocationSettings
import java.security.MessageDigest

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    gatewayAddress: String?,
    hasLocationPermission: Boolean,
    approximateLocationOnly: Boolean,
    amapKeyLabel: String,
    amapConsent: Boolean,
    onRequestLocation: () -> Unit,
    onSaveGateway: (String) -> String?,
    onSaveAmapKey: (String) -> String?,
    onSetAmapConsent: (Boolean) -> Unit,
) {
    var input by remember(gatewayAddress) { mutableStateOf(gatewayAddress.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var amapKeyInput by remember { mutableStateOf("") }
    var amapMessage by remember { mutableStateOf<String?>(null) }
    var amapKeySaved by remember { mutableStateOf(false) }

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
                // Which build is installed, so test packages can be told apart
                // without digging through the APK.
                Text(
                    text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.amap_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.amap_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.amap_privacy_url, AmapLocationSettings.PRIVACY_URL),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = amapConsent,
                        onCheckedChange = onSetAmapConsent,
                    )
                    Text(stringResource(R.string.amap_consent))
                }
                Text(
                    text = stringResource(R.string.amap_key_current, amapKeyLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = amapKeyInput,
                    onValueChange = {
                        amapKeyInput = it
                        amapKeySaved = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.amap_key_hint)) },
                )
                Button(
                    onClick = {
                        val error = onSaveAmapKey(amapKeyInput)
                        amapMessage = error
                        amapKeySaved = error == null
                        if (error == null) amapKeyInput = ""
                    },
                ) { Text(stringResource(R.string.amap_key_save)) }
                if (amapKeySaved) {
                    Text(
                        text = stringResource(R.string.amap_key_saved),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                amapMessage?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = stringResource(R.string.amap_console_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = signingIdentity(LocalContext.current),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Package name and signing SHA1 of the installed build.
 *
 * An AMap **Android** key is bound to exactly these two values, so they are shown
 * where the key is entered instead of being dug out of the machine that built
 * the APK - the debug and release certificates have different fingerprints, and
 * entering the wrong one produces a key-verification error at runtime.
 */
private fun signingIdentity(context: Context): String {
    val signatures = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
        }
    } catch (error: Throwable) {
        null
    }

    val sha1 = signatures?.firstOrNull()?.toByteArray()?.let { bytes ->
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString(":") { "%02X".format(it) }
    } ?: "unknown"

    return "包名 ${context.packageName}\n签名 SHA1 $sha1"
}

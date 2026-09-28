package io.github.bloodbear111.motogps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bloodbear111.motogps.R
import io.github.bloodbear111.motogps.protocol.GoldenCheckResult

/**
 * Runs the reviewed v1 golden vectors through the shared C++ codec on the phone.
 *
 * This is the deliverable's "protocol self-check": it proves the Android build
 * links the *same* encoder, decoder, CRC and reassembler as the firmware, rather
 * than a Kotlin re-implementation that merely looks compatible.
 */
@Composable
fun SelfTestScreen(
    modifier: Modifier = Modifier,
    report: SelfTestReport,
    onRun: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onRun, enabled = !report.running) {
            Text(
                text = stringResource(
                    if (report.running) R.string.selftest_running
                    else R.string.selftest_run,
                ),
            )
        }
        Text(
            text = stringResource(R.string.selftest_intro),
            style = MaterialTheme.typography.bodySmall,
        )

        if (!report.nativeAvailable) {
            Text(
                text = report.error?.let {
                    stringResource(R.string.selftest_native_missing_detail, it)
                } ?: stringResource(R.string.selftest_native_missing),
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            if (report.checks.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.selftest_summary,
                        report.passedCount,
                        report.failedCount,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            report.error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(report.checks, key = { it.name }) { check ->
                    CheckRow(check)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: GoldenCheckResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (check.passed) R.string.selftest_pass else R.string.selftest_fail,
                    check.name,
                ),
                color = if (check.passed) Color(0xFF7BD88F) else MaterialTheme.colorScheme.error,
            )
            Text(text = check.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

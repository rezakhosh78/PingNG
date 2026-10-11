package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.core.AwgEndpointScanState
import com.v2ray.ang.core.EndpointScannerCli

@Composable
internal fun AutoScanEndpointSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Auto Scan Endpoint", style = MaterialTheme.typography.bodyLarge)
            Text("Scan on every connect", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun EndpointScanPanel(
    guid: String,
    state: AwgEndpointScanState.Snapshot,
    scanInProgress: Boolean,
    candidateGroups: List<Pair<String, List<EndpointScannerCli.EndpointCandidate>>>,
    selectedEndpoints: Map<String, String> = emptyMap(),
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onSelect: (String, EndpointScannerCli.EndpointCandidate) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(enabled = !scanInProgress, onClick = onScan) { Text("Scan Endpoint") }
        if (state.guid == guid && state.running) {
            val status = if (state.totalCount > 0 &&
                state.message.contains(Regex("(?i)\\bScanned\\s+\\d+\\s*(?:/|of)\\s*\\d+"))
            ) "Scanning endpoints…" else state.message
            Text(status, fontSize = 12.sp, maxLines = 2)
            if (state.totalCount > 0) {
                Text("Scanned: ${state.scannedCount}/${state.totalCount}", fontSize = 12.sp)
            }
            Text("Found: ${state.endpoints.size}", fontSize = 12.sp)
            Column(Modifier.fillMaxWidth().height(72.dp).verticalScroll(rememberScrollState())) {
                state.endpoints.forEach { Text(it, fontSize = 12.sp) }
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text("Cancel Scan") }
        }
        candidateGroups.forEach { (title, candidates) ->
            if (candidates.isNotEmpty()) {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleSmall)
                Text("Reachable endpoints · lowest ping first", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().height(192.dp).verticalScroll(rememberScrollState())) {
                    candidates.forEachIndexed { index, candidate ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !scanInProgress,
                            onClick = { onSelect(title, candidate) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${index + 1}. ${candidate.endpoint}" +
                                        if (selectedEndpoints[title] == candidate.endpoint) " ✓" else "",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(if (candidate.latencyMs >= 0) "${candidate.latencyMs} ms" else "—")
                            }
                        }
                    }
                }
            }
        }
    }
}

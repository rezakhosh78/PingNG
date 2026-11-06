package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.core.AwgEndpointScanState
import com.v2ray.ang.core.EndpointIpFamily
import com.v2ray.ang.core.EndpointPortScanMode
import com.v2ray.ang.core.EndpointScanOptions
import com.v2ray.ang.core.EndpointScanSettings
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
    showPortScanMode: Boolean = true,
    onScan: (EndpointScanOptions) -> Unit,
    onCancel: () -> Unit,
    onGenerateKey: (() -> Unit)? = null,
    generateKeyLabel: String = "Generate WARP Key",
    onSelect: (String, EndpointScannerCli.EndpointCandidate) -> Unit,
) {
    val saved = remember(guid) { EndpointScanSettings.load(guid) }
    val isPersian = LocalContext.current.resources.configuration.locales[0].language == "fa"
    val actionsLayoutDirection = if (isPersian) LayoutDirection.Rtl else LayoutDirection.Ltr
    var timeoutSeconds by rememberSaveable(guid) { mutableStateOf(saved.timeoutSeconds.toString()) }
    var parallelJobs by rememberSaveable(guid) { mutableStateOf(saved.parallelJobs.toString()) }
    var ipFamily by rememberSaveable(guid) { mutableStateOf(saved.ipFamily.name) }
    var portScanMode by rememberSaveable(guid) { mutableStateOf(saved.portScanMode.name) }
    fun currentOptions() = EndpointScanOptions(
        timeoutSeconds = timeoutSeconds.toIntOrNull()?.coerceIn(1, 600) ?: 2,
        parallelJobs = parallelJobs.toIntOrNull()?.coerceIn(1, 256) ?: 30,
        ipFamily = runCatching { EndpointIpFamily.valueOf(ipFamily) }.getOrDefault(EndpointIpFamily.IPV4),
        portScanMode = runCatching { EndpointPortScanMode.valueOf(portScanMode) }
            .getOrDefault(EndpointPortScanMode.FIRST_REACHABLE),
    )
    fun persist() = EndpointScanSettings.save(guid, currentOptions())

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.endpoint_scan_options_title), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanNumberField(
                value = timeoutSeconds,
                onValueChange = { timeoutSeconds = it; persist() },
                label = stringResource(R.string.endpoint_scan_timeout_seconds),
                modifier = Modifier.weight(1f),
            )
            ScanNumberField(
                value = parallelJobs,
                onValueChange = { parallelJobs = it; persist() },
                label = stringResource(R.string.endpoint_scan_parallel_jobs),
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(R.string.endpoint_scan_ip_family), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ipFamily == EndpointIpFamily.IPV4.name,
                onClick = { ipFamily = EndpointIpFamily.IPV4.name; persist() },
                label = { Text(stringResource(R.string.endpoint_scan_ipv4)) },
            )
            FilterChip(
                selected = ipFamily == EndpointIpFamily.IPV6.name,
                onClick = { ipFamily = EndpointIpFamily.IPV6.name; persist() },
                label = { Text(stringResource(R.string.endpoint_scan_ipv6)) },
            )
        }
        if (showPortScanMode) {
            Text(stringResource(R.string.endpoint_scan_port_mode), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EndpointPortScanMode.values().forEach { mode ->
                    FilterChip(
                        selected = portScanMode == mode.name,
                        onClick = { portScanMode = mode.name; persist() },
                        label = {
                            Text(
                                when (mode) {
                                    EndpointPortScanMode.FIRST_REACHABLE -> stringResource(R.string.endpoint_scan_first_reachable)
                                    EndpointPortScanMode.ALL_OPEN_PORTS -> stringResource(R.string.endpoint_scan_all_open_ports)
                                    EndpointPortScanMode.ALL_KNOWN_PORTS -> stringResource(R.string.endpoint_scan_all_known_ports)
                                },
                            )
                        },
                    )
                }
            }
            when (portScanMode) {
                EndpointPortScanMode.ALL_OPEN_PORTS.name ->
                    Text(stringResource(R.string.endpoint_scan_all_open_ports_hint), style = MaterialTheme.typography.bodySmall)
                EndpointPortScanMode.ALL_KNOWN_PORTS.name ->
                    Text(stringResource(R.string.endpoint_scan_all_known_ports_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        val showCancel = state.guid == guid && state.running
        val actionCount = 1 + (if (showCancel) 1 else 0) + (if (onGenerateKey != null) 1 else 0)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val gap = 8.dp
            val preferredButtonWidth = 136.dp
            val availableButtonWidth = (
                (maxWidth - gap * (actionCount - 1).toFloat()) / actionCount.toFloat()
            )
                .coerceAtLeast(80.dp)
            val buttonWidth = minOf(preferredButtonWidth, availableButtonWidth)
            val buttonPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)

            CompositionLocalProvider(LocalLayoutDirection provides actionsLayoutDirection) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        modifier = Modifier.width(buttonWidth).height(52.dp),
                        contentPadding = buttonPadding,
                        onClick = {
                            persist()
                            onScan(currentOptions())
                        },
                    ) {
                        Text(
                            stringResource(R.string.endpoint_scan_action),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                    onGenerateKey?.let { generateKey ->
                        Button(
                            modifier = Modifier.width(buttonWidth).height(52.dp),
                            contentPadding = buttonPadding,
                            onClick = generateKey,
                        ) {
                            Text(
                                generateKeyLabel,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (showCancel) {
                        OutlinedButton(
                            modifier = Modifier.width(buttonWidth).height(52.dp),
                            contentPadding = buttonPadding,
                            onClick = onCancel,
                        ) {
                            Text(
                                stringResource(R.string.endpoint_scan_cancel_action),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
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

@Composable
private fun ScanNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

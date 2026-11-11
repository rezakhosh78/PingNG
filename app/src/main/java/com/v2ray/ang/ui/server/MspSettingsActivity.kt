package com.v2ray.ang.ui.server

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.core.MspManager
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MSP controls for VLESS WS/TLS; scan work is delegated to SIMORGH's RKh-MSP service. */
class MspSettingsActivity : BaseComponentActivity() {
    private val guid by lazy { intent.getStringExtra(EXTRA_GUID).orEmpty() }
    private val profile by lazy { MmkvManager.decodeServerConfig(guid) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (guid.isBlank() || profile == null) {
            toast(R.string.pingng_msp_profile_unavailable)
            finish()
        }
    }

    @Composable
    override fun ScreenContent() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val currentProfile = remember(guid) { profile }
        val scanner = remember(guid) { MspManager.loadScannerSettings(this) }
        val ispOptions = remember { MspManager.availableIsps(this) }
        val sniOptions = remember { MspManager.availableSnis(this) }
        val selectedIsps = remember(guid) { mutableStateListOf<String>().apply { addAll(scanner.selectedIsps) } }
        var ispSearchQuery by rememberSaveable(guid) { mutableStateOf("") }
        var selectedSnis by remember(guid) { mutableStateOf(scanner.selectedSnis.joinToString("\n")) }
        var scanPort by remember(guid) { mutableStateOf(scanner.scanPort.toString()) }
        var maxScanIps by remember(guid) { mutableStateOf(scanner.maxScanIps.toString()) }
        var scanSpeed by remember(guid) { mutableStateOf(scanner.scanSpeed) }
        var scanWorkers by remember(guid) { mutableStateOf(scanner.scanWorkers.toString()) }
        var manualCleanMode by remember(guid) { mutableStateOf(scanner.manualCleanMode) }
        var manualCleanIps by remember(guid) { mutableStateOf(scanner.manualCleanIps) }
        var manualRangeMode by remember(guid) { mutableStateOf(scanner.manualRangeMode) }
        var manualRanges by remember(guid) { mutableStateOf(scanner.manualRanges) }
        var proxyProtocol by remember(guid) { mutableStateOf(scanner.proxyProtocol) }
        var routeStrategy by remember(guid) { mutableStateOf(scanner.routeStrategy) }
        var mode by remember(guid) { mutableStateOf(MspManager.mode(this, guid)) }
        var scanBeforeConnect by remember(guid, mode) { mutableStateOf(MspManager.scanBeforeConnect(this, guid)) }
        var pingBeforeConnect by remember(guid, mode) { mutableStateOf(MspManager.pingBeforeConnect(this, guid)) }
        var pingRunning by rememberSaveable(guid) { mutableStateOf(false) }
        var pingStatus by rememberSaveable(guid) { mutableStateOf("") }
        var connectionBusy by rememberSaveable(guid) { mutableStateOf(false) }
        var scanCompletedHere by rememberSaveable(guid) { mutableStateOf(false) }
        var scanMessage by rememberSaveable(guid) { mutableStateOf("") }
        var showScannerSettings by rememberSaveable(guid) { mutableStateOf(false) }
        var addressRevision by rememberSaveable(guid) { mutableIntStateOf(0) }
        var scanSnapshot by remember(guid, mode) { mutableStateOf(MspManager.scanSnapshot(this, guid, mode)) }
        val selectedIspKeys = selectedIsps.map { it.lowercase(java.util.Locale.US) }.toSet()
        val filteredIspOptions = remember(ispOptions, ispSearchQuery, selectedIspKeys) {
            ispOptions.filter { ispSearchQuery.isBlank() || it.contains(ispSearchQuery.trim(), ignoreCase = true) }
                .sortedWith(compareByDescending<String> { it.lowercase(java.util.Locale.US) in selectedIspKeys }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it })
        }
        val addresses = remember(guid, mode, addressRevision, currentProfile) {
            MspManager.addressRows(this, guid, cloudflare = mode == MspManager.MODE_CLOUDFLARE, profile = currentProfile)
        }
        val listState = rememberLazyListState()
        val supported = currentProfile?.let(MspManager::isSupported) == true

        LaunchedEffect(guid, mode) {
            while (isActive) {
                val next = withContext(Dispatchers.IO) {
                    MspManager.scanSnapshot(this@MspSettingsActivity, guid, mode)
                }
                // Connection-time pings are written by the runtime as they arrive.
                // Refresh rows even when no scan is active or the scan counters are unchanged.
                addressRevision++
                scanSnapshot = next
                delay(350L)
            }
        }

        fun currentSettings() = MspManager.ScannerSettings(
            selectedIsps = selectedIsps.toList(),
            selectedSnis = selectedSnis.lineSequence().flatMap { it.split(',', ';') }.map(String::trim).filter(String::isNotBlank).toList(),
            scanPort = scanPort.toIntOrNull()?.coerceIn(1, 65535) ?: 443,
            maxScanIps = maxScanIps.toIntOrNull()?.coerceIn(0, 33000) ?: 0,
            scanSpeed = scanSpeed,
            scanWorkers = scanWorkers.toIntOrNull()?.coerceIn(1, 1000) ?: scanner.scanWorkers,
            manualCleanMode = manualCleanMode,
            manualCleanIps = manualCleanIps,
            manualRangeMode = manualRangeMode,
            manualRanges = manualRanges,
            proxyProtocol = proxyProtocol,
            routeStrategy = routeStrategy,
        )

        fun saveSettings() {
            MspManager.saveScannerSettings(this, currentSettings())
            MspManager.setMode(this, guid, mode)
            MspManager.setScanBeforeConnect(this, guid, scanBeforeConnect)
            MspManager.setPingBeforeConnect(this, guid, pingBeforeConnect)
            toast(R.string.pingng_msp_settings_saved)
            finish()
        }

        fun startConnection(targetMode: String, addressToken: String? = null) {
            if (!supported || currentProfile?.mspEnabled != true) {
                toast(R.string.pingng_msp_enable_first)
                return
            }
            MspManager.saveScannerSettings(this, currentSettings())
            MspManager.setMode(this, guid, targetMode)
            mode = targetMode
            if (addressToken != null) MspManager.queueAddressConnection(this, guid, addressToken)
            else MspManager.queueConnection(this, guid)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_MSP_CONNECT_GUID, guid),
            )
            finish()
        }

        fun scanOnly(targetMode: String) {
            if (!supported || currentProfile?.mspEnabled != true || scanSnapshot.running) return
            MspManager.saveScannerSettings(this, currentSettings())
            MspManager.setMode(this, guid, targetMode)
            mode = targetMode
            scanCompletedHere = false
            scanMessage = getString(R.string.pingng_msp_scan_starting)
            runCatching { MspManager.startScan(this, guid, currentProfile, targetMode) }
                .onFailure { scanMessage = getString(R.string.pingng_msp_scan_failed) }
                .onSuccess {
                    scope.launch {
                        var stoppedScan = false
                        try {
                            withContext(Dispatchers.IO) {
                                MspManager.awaitCurrentScan(this@MspSettingsActivity, guid, targetMode)
                            }
                            if (mode == targetMode) {
                                val finished = MspManager.scanSnapshot(this@MspSettingsActivity, guid, targetMode)
                                stoppedScan = finished.status == "stopped"
                                scanCompletedHere = finished.complete
                                scanMessage = getString(
                                    if (finished.status == "stopped") R.string.pingng_msp_scan_stopped
                                    else R.string.pingng_msp_scan_finished,
                                )
                                addressRevision++
                            }
                            if (targetMode == MspManager.MODE_CLOUDFLARE && mode == targetMode && !stoppedScan) {
                                pingRunning = true
                                pingStatus = getString(R.string.pingng_msp_ping_running)
                                val results = withContext(Dispatchers.IO) {
                                    MspManager.pingForConnection(this@MspSettingsActivity, guid, currentProfile, freshScan = true) { done, total ->
                                        runOnUiThread {
                                            addressRevision++; pingStatus = getString(R.string.pingng_msp_ping_progress, done, total, minOf(total - done, 12 - done % 12))
                                        }
                                    }
                                }
                                pingStatus = getString(R.string.pingng_msp_ping_complete, results.values.count { it >= 0L }, results.size)
                                addressRevision++
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            if (mode == targetMode) scanMessage = getString(R.string.pingng_msp_scan_failed)
                        } finally {
                            pingRunning = false
                        }
                    }
                }
        }

        fun toggleScan(targetMode: String) {
            if (scanSnapshot.running) {
                MspManager.stopScan(this, guid, targetMode)
                scanMessage = getString(R.string.pingng_msp_scan_stopping)
            } else {
                scanOnly(targetMode)
            }
        }

        fun connectDirect() {
            if (!supported || currentProfile?.mspEnabled != true || connectionBusy) return
            MspManager.saveScannerSettings(this, currentSettings())
            MspManager.setMode(this, guid, MspManager.MODE_DIRECT)
            mode = MspManager.MODE_DIRECT
            // Let the MSP runtime choose the first live route itself. An explicit row selection
            // pins one address, which would prevent the scanner from feeding the live pool.
            startConnection(MspManager.MODE_DIRECT)
        }

        fun connectCloudflare() {
            if (!supported || currentProfile?.mspEnabled != true || connectionBusy) return
            MspManager.saveScannerSettings(this, currentSettings())
            MspManager.setMode(this, guid, MspManager.MODE_CLOUDFLARE)
            mode = MspManager.MODE_CLOUDFLARE
            if (!scanBeforeConnect && !pingBeforeConnect &&
                MspManager.addressCount(this, guid, MspManager.MODE_CLOUDFLARE) > 0
            ) {
                val best = MspManager.preferredSavedCloudflareAddress(this, guid, currentProfile)
                val token = MspManager.addressRows(this, guid, true, currentProfile)
                    .firstOrNull { it.address == best }?.selectionToken
                if (token != null) startConnection(MspManager.MODE_CLOUDFLARE, token)
                else pingStatus = getString(R.string.pingng_msp_ping_failed)
                return
            }
            connectionBusy = true
            scope.launch {
                runCatching {
                    if (scanBeforeConnect && !scanCompletedHere) {
                        scanMessage = getString(R.string.pingng_msp_scan_starting)
                        if (!MspManager.scanInProgress(this@MspSettingsActivity, guid, MspManager.MODE_CLOUDFLARE)) {
                            MspManager.startScan(this@MspSettingsActivity, guid, currentProfile, MspManager.MODE_CLOUDFLARE)
                        }
                        withContext(Dispatchers.IO) {
                            MspManager.awaitCurrentScan(this@MspSettingsActivity, guid, MspManager.MODE_CLOUDFLARE)
                        }
                        scanCompletedHere = true
                        scanMessage = getString(R.string.pingng_msp_scan_finished)
                    }
                    pingRunning = true
                    pingStatus = getString(R.string.pingng_msp_ping_running)
                    withContext(Dispatchers.IO) {
                        MspManager.pingAll(this@MspSettingsActivity, guid, currentProfile) { done, total ->
                            runOnUiThread { addressRevision++; pingStatus = getString(R.string.pingng_msp_ping_progress, done, total, minOf(total - done, 12 - done % 12)) }
                        }
                    }
                }.onSuccess { results ->
                    addressRevision++
                    val best = results.filterValues { it >= 0L }.minByOrNull { it.value }?.key
                    val token = MspManager.addressRows(this@MspSettingsActivity, guid, true, currentProfile)
                        .firstOrNull { it.address == best }?.selectionToken
                    if (token != null) startConnection(MspManager.MODE_CLOUDFLARE, token)
                    else pingStatus = getString(R.string.pingng_msp_ping_failed)
                }.onFailure { pingStatus = getString(R.string.pingng_msp_ping_failed) }
                pingRunning = false
                connectionBusy = false
            }
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.pingng_msp_toggle),
                    onBackClick = { finish() },
                    actions = {
                        androidx.compose.material3.TextButton(onClick = ::saveSettings) {
                            Text(stringResource(R.string.acc_save))
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScrollbar(listState),
                contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    SettingsSwitchItem(
                        title = stringResource(R.string.pingng_msp_scan_before_connect),
                        summary = stringResource(R.string.pingng_msp_scan_before_connect_summary),
                        checked = scanBeforeConnect,
                        onCheckedChange = { scanBeforeConnect = it; MspManager.setScanBeforeConnect(this@MspSettingsActivity, guid, it) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item {
                    SettingsSwitchItem(
                        title = stringResource(R.string.pingng_msp_ping_before_connect),
                        summary = stringResource(R.string.pingng_msp_ping_before_connect_summary),
                        checked = pingBeforeConnect,
                        onCheckedChange = { pingBeforeConnect = it; MspManager.setPingBeforeConnect(this@MspSettingsActivity, guid, it) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = mode == MspManager.MODE_CLOUDFLARE,
                                onClick = { mode = MspManager.MODE_CLOUDFLARE; scanCompletedHere = false; scanMessage = ""; MspManager.setMode(this@MspSettingsActivity, guid, mode) },
                                label = { Text(stringResource(R.string.pingng_msp_cloudflare)) },
                            )
                            FilterChip(
                                selected = mode == MspManager.MODE_DIRECT,
                                onClick = { mode = MspManager.MODE_DIRECT; scanCompletedHere = false; scanMessage = ""; MspManager.setMode(this@MspSettingsActivity, guid, mode) },
                                label = { Text(stringResource(R.string.pingng_msp_direct)) },
                            )
                        }
                    }
                }
                if (mode == MspManager.MODE_DIRECT) item {
                    MspSection(title = stringResource(R.string.pingng_msp_direct)) {
                        Text(
                            stringResource(R.string.pingng_msp_direct_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { toggleScan(MspManager.MODE_DIRECT) },
                                enabled = supported && currentProfile?.mspEnabled == true && !connectionBusy && !pingRunning,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(if (scanSnapshot.running) R.string.pingng_msp_stop_scan else R.string.pingng_msp_scan_now)) }
                            Button(
                                onClick = ::connectDirect,
                                enabled = supported && currentProfile?.mspEnabled == true && !connectionBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.pingng_msp_connect_direct)) }
                        }
                        ScanProgress(scanSnapshot, scanMessage)
                        AddressList(
                            title = stringResource(R.string.pingng_msp_found_addresses),
                            rows = addresses,
                            onSelect = { token -> if (!connectionBusy) startConnection(MspManager.MODE_DIRECT, token) },
                            onClear = {
                                MspManager.clearSavedAddresses(this@MspSettingsActivity, guid, MspManager.MODE_DIRECT)
                                scanCompletedHere = false
                                addressRevision++
                                scanSnapshot = MspManager.scanSnapshot(this@MspSettingsActivity, guid, MspManager.MODE_DIRECT)
                                scanMessage = ""
                            },
                            clearEnabled = !scanSnapshot.running && !connectionBusy,
                        )
                    }
                }
                if (mode == MspManager.MODE_CLOUDFLARE) item {
                    MspSection(title = stringResource(R.string.pingng_msp_cloudflare)) {
                        Text(
                            stringResource(R.string.pingng_msp_cloudflare_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { toggleScan(MspManager.MODE_CLOUDFLARE) },
                                enabled = supported && currentProfile?.mspEnabled == true && !connectionBusy && !pingRunning,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(if (scanSnapshot.running) R.string.pingng_msp_stop_scan else R.string.pingng_msp_scan_now)) }
                            Button(
                                onClick = ::connectCloudflare,
                                enabled = supported && currentProfile?.mspEnabled == true && !connectionBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.pingng_msp_connect_cloudflare)) }
                        }
                        ScanProgress(scanSnapshot, scanMessage)
                        OutlinedButton(
                            onClick = {
                                if (!supported || currentProfile == null) return@OutlinedButton
                                pingRunning = true
                                pingStatus = getString(R.string.pingng_msp_ping_running)
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            MspManager.pingAll(this@MspSettingsActivity, guid, currentProfile) { done, total ->
                                                runOnUiThread { addressRevision++; pingStatus = getString(R.string.pingng_msp_ping_progress, done, total, minOf(total - done, 12 - done % 12)) }
                                            }
                                        }
                                    }.onSuccess { results ->
                                        pingStatus = getString(R.string.pingng_msp_ping_complete, results.values.count { it >= 0L }, results.size)
                                        addressRevision++
                                    }.onFailure { pingStatus = getString(R.string.pingng_msp_ping_failed) }
                                    pingRunning = false
                                }
                            },
                            enabled = !pingRunning && !connectionBusy && !scanSnapshot.running && supported && addresses.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.pingng_msp_ping_all)) }
                        if (pingStatus.isNotBlank()) {
                            Text(pingStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AddressList(
                            title = stringResource(R.string.pingng_msp_found_addresses),
                            rows = addresses,
                            onSelect = { token -> if (!connectionBusy) startConnection(MspManager.MODE_CLOUDFLARE, token) },
                            onClear = {
                                MspManager.clearSavedAddresses(this@MspSettingsActivity, guid, MspManager.MODE_CLOUDFLARE)
                                scanCompletedHere = false
                                addressRevision++
                                scanSnapshot = MspManager.scanSnapshot(this@MspSettingsActivity, guid, MspManager.MODE_CLOUDFLARE)
                                pingStatus = ""
                                scanMessage = ""
                            },
                            clearEnabled = !scanSnapshot.running && !connectionBusy && !pingRunning,
                        )
                    }
                }
                item {
                    Button(
                        onClick = { showScannerSettings = !showScannerSettings },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) { Text(stringResource(if (showScannerSettings) R.string.pingng_msp_hide_scanner_settings else R.string.pingng_msp_scanner_settings)) }
                }
                if (showScannerSettings) item {
                    MspSection(title = stringResource(R.string.pingng_msp_scanner_settings)) {
                        val scanSpeedOptions = listOf(
                            "slow" to stringResource(R.string.pingng_msp_speed_slow),
                            "normal" to stringResource(R.string.pingng_msp_speed_normal),
                            "fast" to stringResource(R.string.pingng_msp_speed_fast),
                            "custom" to stringResource(R.string.pingng_msp_speed_custom),
                        )
                        FormTextField(
                            label = stringResource(R.string.pingng_msp_isp_option),
                            value = ispSearchQuery,
                            onValueChange = { ispSearchQuery = it },
                            maxLines = 1,
                        )
                        if (filteredIspOptions.isEmpty()) {
                            Text(
                                stringResource(R.string.pingng_msp_isp_no_matches),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (selectedIsps.isEmpty()) {
                                Text(
                                    stringResource(R.string.pingng_msp_no_isp_selected),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val ispListState = rememberLazyListState()
                            LazyColumn(
                                state = ispListState,
                                modifier = Modifier.fillMaxWidth().height(220.dp).verticalScrollbar(ispListState),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                items(filteredIspOptions, key = { it }) { isp ->
                                    val checked = isp.lowercase(java.util.Locale.US) in selectedIspKeys
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                if (checked) selectedIsps.removeAll { it.equals(isp, ignoreCase = true) }
                                                else selectedIsps.add(isp)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { enabled ->
                                                if (enabled) {
                                                    if (!checked) selectedIsps.add(isp)
                                                } else {
                                                    selectedIsps.removeAll { it.equals(isp, ignoreCase = true) }
                                                }
                                            },
                                        )
                                        Text(isp, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        FormTextField(
                            label = stringResource(R.string.pingng_msp_sni_list),
                            value = selectedSnis,
                            onValueChange = { selectedSnis = it },
                            supportingText = stringResource(R.string.pingng_msp_lines_hint),
                        )
                        if (sniOptions.isNotEmpty()) {
                            FormDropdownField(
                                label = stringResource(R.string.pingng_msp_sni_catalog),
                                value = sniOptions.firstOrNull { it in selectedSnis } ?: sniOptions.first(),
                                options = sniOptions,
                                onValueChange = { value ->
                                    val current = selectedSnis.lineSequence().map(String::trim).filter(String::isNotBlank).toMutableList()
                                    if (current.none { it.equals(value, true) }) current.add(value)
                                    selectedSnis = current.joinToString("\n")
                                },
                            )
                        }
                        FormTextField(stringResource(R.string.pingng_msp_scan_port), scanPort, { scanPort = it }, keyboardType = KeyboardType.Number, maxLines = 1)
                        FormTextField(stringResource(R.string.pingng_msp_max_scan), maxScanIps, { maxScanIps = it }, keyboardType = KeyboardType.Number, maxLines = 1)
                        FormDropdownField(
                            label = stringResource(R.string.pingng_msp_scan_speed),
                            value = scanSpeedOptions.firstOrNull { it.first == scanSpeed }?.second ?: scanSpeedOptions[1].second,
                            options = scanSpeedOptions.map { it.second },
                            onValueChange = {
                                val selectedSpeed = scanSpeedOptions.firstOrNull { option -> option.second == it }?.first ?: "normal"
                                scanSpeed = selectedSpeed
                                if (selectedSpeed != "custom") {
                                    scanWorkers = when (selectedSpeed) {
                                        "slow" -> "48"
                                        "fast" -> "400"
                                        else -> "160"
                                    }
                                }
                            },
                        )
                        FormTextField(
                            label = stringResource(R.string.pingng_msp_scan_workers),
                            value = scanWorkers,
                            onValueChange = { scanWorkers = it.filter(Char::isDigit).take(4) },
                            enabled = scanSpeed == "custom",
                            keyboardType = KeyboardType.Number,
                            maxLines = 1,
                            onFocusLost = {
                                scanWorkers = scanWorkers.toIntOrNull()?.coerceIn(1, 1000)?.toString()
                                    ?: scanner.scanWorkers.toString()
                            },
                        )
                        Text(
                            stringResource(R.string.pingng_msp_scan_workers_range),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                        )
                        SettingsSwitchItem(
                            title = stringResource(R.string.pingng_msp_manual_clean_mode),
                            summary = stringResource(R.string.pingng_msp_manual_clean_summary),
                            checked = manualCleanMode,
                            onCheckedChange = { manualCleanMode = it },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        if (manualCleanMode) {
                            FormTextField(stringResource(R.string.pingng_msp_manual_clean_ips), manualCleanIps, { manualCleanIps = it }, supportingText = stringResource(R.string.pingng_msp_lines_hint))
                        }
                        SettingsSwitchItem(
                            title = stringResource(R.string.pingng_msp_manual_range_mode),
                            summary = stringResource(R.string.pingng_msp_manual_range_summary),
                            checked = manualRangeMode,
                            onCheckedChange = { manualRangeMode = it },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        if (manualRangeMode) {
                            FormTextField(stringResource(R.string.pingng_msp_manual_ranges), manualRanges, { manualRanges = it }, supportingText = stringResource(R.string.pingng_msp_lines_hint))
                        }
                        FormDropdownField(
                            label = stringResource(R.string.pingng_msp_proxy_protocol),
                            value = proxyProtocol,
                            options = listOf("socks5", "http"),
                            onValueChange = { proxyProtocol = it },
                        )
                        if (mode == MspManager.MODE_DIRECT) {
                            FormDropdownField(
                                label = stringResource(R.string.pingng_msp_route_strategy),
                                value = routeStrategy,
                                options = listOf("default", "lowest_latency", "round_robin", "random", "least_loss", "hybrid_score"),
                                onValueChange = { routeStrategy = it },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                item {
                    Button(onClick = ::saveSettings, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.pingng_msp_save_settings))
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_GUID = "msp_profile_guid"
    }
}

@Composable
private fun MspSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ScanProgress(snapshot: MspManager.ScanSnapshot, message: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.pingng_msp_live_scan), style = MaterialTheme.typography.titleSmall)
        val status = when {
            snapshot.running && snapshot.total == 0 -> stringResource(R.string.pingng_msp_scan_starting)
            snapshot.running -> stringResource(R.string.pingng_msp_scan_running)
            snapshot.status == "stopped" -> stringResource(R.string.pingng_msp_scan_stopped)
            snapshot.complete -> stringResource(R.string.pingng_msp_scan_finished)
            else -> stringResource(R.string.pingng_msp_scan_idle)
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.pingng_msp_scanned_count, snapshot.scanned, snapshot.total), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.pingng_msp_found_count, snapshot.found), style = MaterialTheme.typography.bodySmall)
        }
        if (snapshot.running || snapshot.total > 0) {
            if (snapshot.total > 0) {
                LinearProgressIndicator(progress = { (snapshot.scanned.toFloat() / snapshot.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (message.isNotBlank() && message != status) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddressList(
    title: String,
    rows: List<MspManager.AddressRow>,
    onSelect: ((String) -> Unit)?,
    onClear: () -> Unit,
    clearEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear, enabled = clearEnabled) {
                Text(stringResource(R.string.pingng_msp_clear))
            }
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.pingng_msp_no_addresses),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(250.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.address }) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (onSelect != null) Modifier.clickable { onSelect(row.selectionToken) } else Modifier)
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        if (row.delayMillis != null && row.delayMillis >= 0L) {
                            Text("${row.delayMillis} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        } else if (row.delayMillis != null) {
                            Text(stringResource(R.string.pingng_msp_ping_timeout), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        } else if (onSelect != null) {
                            Text(stringResource(R.string.pingng_msp_select_address), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
    }
}

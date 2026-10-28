package com.v2ray.ang.ui.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.AwgEndpointScanState
import com.v2ray.ang.core.EndpointScannerCli
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.WarpShareCodec
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.core.WarpProxyChoiceLabel
import com.v2ray.ang.core.WarpProxyChoiceLabels
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionProfileOverrides
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.SmartDesyncArgumentsEditor
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.ui.main.DesyncSearchDialog
import java.util.UUID
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v2ray.ang.fmt.AmneziaWgFmt

/** Editor for a real WARP MASQUE/H2 profile backed by the standalone core. */
class WarpMasqueActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.WARP
    private var warpSearchDraftGuid: String? = null

    @Composable
    override fun ScreenContent() {
        val scope = rememberCoroutineScope()
        val scanGuid = rememberSaveable(editGuid) {
            editGuid.ifBlank {
                warpSearchDraftGuid ?: UUID.randomUUID().toString().also { warpSearchDraftGuid = it }
            }
        }
        warpSearchDraftGuid = scanGuid
        val scanState by AwgEndpointScanState.state.collectAsStateWithLifecycle()
        var scanJob by remember { mutableStateOf<Job?>(null) }
        var scannedCandidates by rememberSaveable { mutableStateOf(initialConfig.awgEndpointCandidates.orEmpty()) }
        val candidateList = remember(scannedCandidates) { EndpointScannerCli.decodeCandidates(scannedCandidates) }
        var autoScanEndpoint by rememberSaveable {
            mutableStateOf(WarpMasqueConfig.shouldAutoScan(initialConfig))
        }
        var remarks by rememberSaveable { mutableStateOf(initialConfig.remarks.ifBlank { "WARP MASQUE/H2" }) }
        var registrationProxyGuid by rememberSaveable {
            mutableStateOf(WarpRegistrationProxy.normalizeSelection(initialConfig.warpRegistrationProxyGuid))
        }
        val proxyChoices = remember(editGuid) { WarpRegistrationProxy.choices(editGuid) }
        val proxyChoiceLabels = remember(proxyChoices) {
            proxyChoices.map { WarpProxyChoiceLabel(it.guid, it.label) }
        }
        val effectiveRegistrationProxyGuid = WarpProxyChoiceLabels.normalizeGuid(
            WarpRegistrationProxy.normalizeSelection(registrationProxyGuid),
            proxyChoiceLabels,
            WarpRegistrationProxy.AUTO,
        )
        val selectedProxyLabel = WarpProxyChoiceLabels.labelForGuid(
            effectiveRegistrationProxyGuid,
            proxyChoiceLabels,
            WarpRegistrationProxy.AUTO,
            WarpRegistrationProxy.AUTO_LABEL,
        )
        var endpoints by rememberSaveable {
            val stored = initialConfig.warpMasqueEndpointCandidates?.trim()
            mutableStateOf(
                if (stored.isNullOrBlank() || stored == WarpMasqueConfig.LEGACY_DEFAULT_ENDPOINTS) {
                    WarpMasqueConfig.DEFAULT_ENDPOINTS
                } else {
                    stored
                },
            )
        }
        val editorEndpoint = remember(initialConfig) {
            WarpMasqueConfig.editorEndpoint(initialConfig)
        }
        var primaryEndpoint by rememberSaveable {
            mutableStateOf(editorEndpoint.host)
        }
        var endpointPort by rememberSaveable {
            mutableStateOf(editorEndpoint.port.toString())
        }
        var deviceName by rememberSaveable {
            mutableStateOf(initialConfig.warpMasqueDeviceName ?: WarpMasqueConfig.randomDeviceName())
        }
        var sni by rememberSaveable {
            mutableStateOf(WarpMasqueConfig.normalizeSni(initialConfig.warpMasqueSni))
        }
        var dns by rememberSaveable {
            mutableStateOf(initialConfig.warpMasqueDns ?: WarpMasqueConfig.DEFAULT_DNS)
        }
        var http2Enabled by rememberSaveable {
            mutableStateOf(initialConfig.warpMasqueHttp2Enabled ?: true)
        }
        var psiphon by rememberSaveable { mutableStateOf(initialConfig.psiphonEnabled) }
        var tor by rememberSaveable { mutableStateOf(initialConfig.torEnabled) }
        var torBridgeMode by rememberSaveable { mutableStateOf(initialConfig.torBridgeMode.orEmpty().ifBlank { "auto" }) }
        var torBridgeSources by rememberSaveable { mutableStateOf(initialConfig.torBridgeSources.orEmpty().ifBlank { "auto" }) }
        var torBridgeLines by rememberSaveable { mutableStateOf(initialConfig.torBridgeLines.orEmpty()) }
        var psiphonRegion by rememberSaveable { mutableStateOf(initialConfig.psiphonRegion ?: "ANY") }
        var psiphonMode by rememberSaveable { mutableStateOf(initialConfig.psiphonMode ?: "auto") }
        var psiphonCdnIps by rememberSaveable { mutableStateOf(initialConfig.psiphonCdnIps.orEmpty()) }
        var psiphonCdnSni by rememberSaveable { mutableStateOf(initialConfig.psiphonCdnSni.orEmpty()) }
        var psiphonCdnSets by rememberSaveable { mutableStateOf(initialConfig.psiphonCdnSets.orEmpty()) }
        var desyncProfile by rememberSaveable {
            mutableStateOf(initialConfig.pingNgProfile ?: PingNgCompat.PROFILE_OFF)
        }
        var desyncArgs by rememberSaveable {
            mutableStateOf(initialConfig.pingNgDesyncArgs.orEmpty())
        }
        var showDelete by rememberSaveable { mutableStateOf(false) }
        var showFindDesync by rememberSaveable { mutableStateOf(false) }
        var desyncSearchGuid by rememberSaveable { mutableStateOf(editGuid) }
        val importFullProfile = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) scope.launch {
                val imported = runCatching {
                    withContext(Dispatchers.IO) {
                        WarpShareCodec.decode(this@WarpMasqueActivity.readTextDocument(uri))
                    } as? WarpShareCodec.Decoded.Masque
                        ?: error(getString(R.string.toast_failure))
                }.getOrElse { error ->
                    toast(error.message ?: getString(R.string.toast_failure))
                    return@launch
                }.profile
                val importedEndpoint = WarpMasqueConfig.editorEndpoint(imported)
                remarks = imported.remarks
                endpoints = imported.warpMasqueEndpointCandidates?.takeIf { it.isNotBlank() }
                    ?: WarpMasqueConfig.DEFAULT_ENDPOINTS
                primaryEndpoint = importedEndpoint.host
                endpointPort = importedEndpoint.port.toString()
                autoScanEndpoint = WarpMasqueConfig.shouldAutoScan(imported)
                scannedCandidates = imported.awgEndpointCandidates.orEmpty()
                deviceName = imported.warpMasqueDeviceName?.takeIf { it.isNotBlank() }
                    ?: WarpMasqueConfig.randomDeviceName()
                sni = WarpMasqueConfig.normalizeSni(imported.warpMasqueSni)
                dns = imported.warpMasqueDns?.takeIf { it.isNotBlank() } ?: WarpMasqueConfig.DEFAULT_DNS
                http2Enabled = imported.warpMasqueHttp2Enabled ?: true
                registrationProxyGuid = WarpRegistrationProxy.normalizeSelection(imported.warpRegistrationProxyGuid)
                psiphon = imported.psiphonEnabled
                tor = imported.torEnabled
                torBridgeMode = imported.torBridgeMode.orEmpty().ifBlank { "auto" }
                torBridgeSources = imported.torBridgeSources.orEmpty().ifBlank { "auto" }
                torBridgeLines = imported.torBridgeLines.orEmpty()
                psiphonRegion = imported.psiphonRegion ?: "ANY"
                psiphonMode = imported.psiphonMode ?: "auto"
                psiphonCdnIps = imported.psiphonCdnIps.orEmpty()
                psiphonCdnSni = imported.psiphonCdnSni.orEmpty()
                psiphonCdnSets = imported.psiphonCdnSets.orEmpty()
                desyncProfile = imported.pingNgProfile ?: PingNgCompat.PROFILE_OFF
                desyncArgs = imported.pingNgDesyncArgs.orEmpty()
                toastSuccess(R.string.toast_success)
            }
        }
        val listState = rememberLazyListState()

        Scaffold(
            topBar = {
                AppTopBar(
                    title = "WARP",
                    onBackClick = { finish() },
                    actions = {
                        IconButton(onClick = {
                            importFullProfile.launch(arrayOf("text/plain", "application/json"))
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_import_profile_24dp),
                                contentDescription = stringResource(R.string.action_import_profile),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        if (editGuid.isNotBlank() && !isRunning) {
                            IconButton(onClick = { showDelete = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete_24dp),
                                    contentDescription = stringResource(R.string.acc_delete),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        IconButton(onClick = {
                            saveServer(
                                remarks = remarks,
                                registrationProxyGuid = effectiveRegistrationProxyGuid,
                                endpoints = endpoints,
                                primaryEndpoint = primaryEndpoint,
                                endpointPort = endpointPort,
                                deviceName = deviceName,
                                sni = sni,
                                dns = dns,
                                http2Enabled = http2Enabled,
                                psiphon = psiphon,
                                tor = tor,
                                torBridgeMode = torBridgeMode,
                                torBridgeSources = torBridgeSources,
                                torBridgeLines = torBridgeLines,
                                psiphonRegion = psiphonRegion,
                                psiphonMode = psiphonMode,
                                psiphonCdnIps = psiphonCdnIps,
                                psiphonCdnSni = psiphonCdnSni,
                                psiphonCdnSets = psiphonCdnSets,
                                desyncProfile = desyncProfile,
                                desyncArgs = desyncArgs,
                                scannedCandidates = scannedCandidates,
                                autoScanEndpoint = autoScanEndpoint,
                            )
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fab_check),
                                contentDescription = stringResource(R.string.acc_save),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding).verticalScrollbar(listState),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (intent.getBooleanExtra(WarpCoreNavigation.SHOW_CORE, false)) {
                    item { WarpCoreField(WarpCoreNavigation.MASQUE, ::switchWarpCore) }
                }
                item {
                    AutoScanEndpointSwitch(
                        checked = autoScanEndpoint,
                        onCheckedChange = { autoScanEndpoint = it },
                    )
                }
                item {
                    EndpointScanPanel(
                        guid = scanGuid,
                        state = scanState,
                        scanInProgress = scanJob?.isActive == true,
                        candidateGroups = listOf("MASQUE/H2" to candidateList),
                        selectedEndpoints = mapOf("MASQUE/H2" to "${com.v2ray.ang.util.Utils.getIpv6Address(primaryEndpoint)}:$endpointPort"),
                        showPortScanMode = false,
                        onScan = { scanOptions ->
                            scanJob?.cancel()
                            EndpointScannerCli.cancelActiveScans()
                            AwgEndpointScanState.clear(scanGuid)
                            scanJob = scope.launch {
                                try {
                                    val found = EndpointScannerCli.scanMasqueH2Endpoints(
                                        this@WarpMasqueActivity,
                                        scanGuid,
                                        WarpMasqueConfig.normalizeSni(sni),
                                        scanOptions = scanOptions,
                                    )
                                    scannedCandidates = EndpointScannerCli.encodeCandidates(found)
                                    val selectedEndpoint = found.first().endpoint
                                    val (host, port) = AmneziaWgFmt.splitEndpoint(selectedEndpoint)
                                    if (!host.isNullOrBlank() && port?.toIntOrNull()?.let { it in 1..65535 } == true) {
                                        primaryEndpoint = host
                                        endpointPort = port
                                    }
                                    AwgEndpointScanState.complete(
                                        scanGuid,
                                        "Endpoint selected: $selectedEndpoint",
                                        selectedEndpoint,
                                    )
                                } catch (cancelled: CancellationException) {
                                    AwgEndpointScanState.clear(scanGuid)
                                    throw cancelled
                                } catch (_: Throwable) {
                                    AwgEndpointScanState.fail(scanGuid, "Endpoint scan failed")
                                }
                            }
                        },
                        onCancel = {
                            scanJob?.cancel()
                            EndpointScannerCli.cancelActiveScans()
                        },
                        onSelect = { _, candidate ->
                            val (host, port) = AmneziaWgFmt.splitEndpoint(candidate.endpoint)
                            if (!host.isNullOrBlank() && !port.isNullOrBlank()) {
                                primaryEndpoint = host
                                endpointPort = port
                                autoScanEndpoint = false
                            }
                        },
                    )
                }
                item { FormTextField(stringResource(R.string.server_lab_remarks), remarks, { remarks = it }) }
                item {
                    FormDropdownField(
                        label = "Proxy",
                        value = selectedProxyLabel,
                        options = listOf(WarpRegistrationProxy.AUTO_LABEL) + proxyChoices.map { it.label },
                        onValueChange = { selected ->
                            registrationProxyGuid = WarpProxyChoiceLabels.guidForLabel(
                                selected,
                                proxyChoiceLabels,
                                WarpRegistrationProxy.AUTO,
                                WarpRegistrationProxy.AUTO_LABEL,
                            )
                        },
                    )
                }
                item {
                    FormTextField(
                        label = "Device name",
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        maxLines = 1,
                    )
                }
                item {
                    FormTextField(
                        label = "Primary endpoint",
                        value = primaryEndpoint,
                        onValueChange = { primaryEndpoint = it; autoScanEndpoint = false },
                        placeholder = WarpMasqueConfig.DEFAULT_ENDPOINT,
                        maxLines = 1,
                        keyboardType = KeyboardType.Uri,
                    )
                }
                item {
                    FormTextField(
                        label = "Endpoint port",
                        value = endpointPort,
                        onValueChange = { endpointPort = it; autoScanEndpoint = false },
                        placeholder = WarpMasqueConfig.DEFAULT_PORT.toString(),
                        maxLines = 1,
                        keyboardType = KeyboardType.Number,
                    )
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("MASQUE over HTTP/2", style = MaterialTheme.typography.titleMedium)
                    }
                }
                item {
                    FormTextField(
                        label = "MASQUE endpoints",
                        value = endpoints,
                        onValueChange = { endpoints = it },
                        placeholder = "162.159.198.0/24:443",
                        maxLines = 4,
                    )
                }
                item {
                    FormTextField(
                        label = "SNI",
                        value = sni,
                        onValueChange = { sni = it },
                        placeholder = WarpMasqueConfig.DEFAULT_SNI,
                        maxLines = 1,
                    )
                }
                item {
                    FormTextField(
                        label = "DNS servers",
                        value = dns,
                        onValueChange = { dns = it },
                        placeholder = WarpMasqueConfig.DEFAULT_DNS,
                        maxLines = 2,
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("HTTP/2", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = http2Enabled, onCheckedChange = { http2Enabled = it })
                    }
                }
                item {
                    PsiphonEditorFields(
                        enabled = psiphon,
                        torEnabled = tor,
                        torBridgeMode = torBridgeMode,
                        onTorBridgeModeChange = { torBridgeMode = it },
                        torBridgeSources = torBridgeSources,
                        onTorBridgeSourcesChange = { torBridgeSources = it },
                        torBridgeLines = torBridgeLines,
                        onTorBridgeLinesChange = { torBridgeLines = it },
                        region = psiphonRegion,
                        onEnabledChange = { psiphon = it; if (it) tor = false },
                        onTorEnabledChange = { tor = it; if (it) psiphon = false },
                        enabledTitle = "Psiphon Over WARP MASQUE/H2",
                        torEnabledTitle = "Tor Over WARP MASQUE/H2",
                        onRegionChange = { psiphonRegion = it },
                        mode = psiphonMode,
                        onModeChange = { psiphonMode = it },
                        cdnIps = psiphonCdnIps,
                        onCdnIpsChange = { psiphonCdnIps = it },
                        cdnSni = psiphonCdnSni,
                        onCdnSniChange = { psiphonCdnSni = it },
                        cdnSets = psiphonCdnSets,
                        onCdnSetsChange = { psiphonCdnSets = it },
                    )
                }
                item {
                    FormDropdownField(
                        label = "Desync profile",
                        value = desyncProfile,
                        options = listOf(
                            PingNgCompat.PROFILE_OFF,
                            PingNgCompat.PROFILE_LIGHT,
                            PingNgCompat.PROFILE_BALANCED,
                            PingNgCompat.PROFILE_SEVERE,
                            PingNgCompat.PROFILE_ADAPTIVE,
                            PingNgCompat.PROFILE_CUSTOM,
                        ),
                        onValueChange = {
                            desyncProfile = it
                            if (it == PingNgCompat.PROFILE_CUSTOM && desyncArgs.isBlank()) {
                                desyncArgs = PingNgCompat.buildCustomArguments(PingNgCompat.CustomOptions())
                            }
                        },
                    )
                }
                item {
                    Button(
                        onClick = {
                            val guid = editGuid.ifBlank {
                                warpSearchDraftGuid ?: UUID.randomUUID().toString().also {
                                    warpSearchDraftGuid = it
                                }
                            }
                            val draft = initialConfig.copy(
                                remarks = remarks.trim(),
                                description = WarpMasqueConfig.DESCRIPTION,
                                server = primaryEndpoint.trim(),
                                serverPort = endpointPort,
                                warpMasquePrimaryEndpoint = primaryEndpoint.trim(),
                                warpMasqueEndpointPort = endpointPort.toIntOrNull(),
                                warpMasqueEndpointMode = if (autoScanEndpoint) {
                                    WarpMasqueConfig.ENDPOINT_MODE_AUTO
                                } else {
                                    WarpMasqueConfig.ENDPOINT_MODE_CUSTOM
                                },
                                warpMasqueEndpointCandidates = endpoints.trim(),
                                warpMasqueDeviceName = deviceName.trim(),
                                warpMasqueSni = sni.trim(),
                                warpMasqueHttp2Enabled = http2Enabled,
                                warpMasqueDns = dns.trim(),
                                pingNgProfile = desyncProfile,
                                pingNgDesyncArgs = desyncArgs.trim().ifBlank { null },
                                warpRegistrationProxyGuid = registrationProxyGuid,
                            )
                            MmkvManager.encodeServerConfig(guid, draft)
                            desyncSearchGuid = guid
                            showFindDesync = true
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.pingng_find_desync_setting))
                    }
                }
                if (desyncProfile == PingNgCompat.PROFILE_CUSTOM) {
                    item {
                        SmartDesyncArgumentsEditor(
                            arguments = desyncArgs,
                            onArgumentsChange = { desyncArgs = it },
                            onReset = {
                                desyncProfile = PingNgCompat.PROFILE_OFF
                                desyncArgs = ""
                            },
                        )
                    }
                }
            }
        }

        if (showDelete) {
            DeleteConfirmDialog(
                message = stringResource(R.string.confirm_delete_profile),
                onDismiss = { showDelete = false },
                onConfirm = { deleteServer() },
            )
        }
        if (showFindDesync) {
            DesyncSearchDialog(
                profiles = listOfNotNull(
                    MmkvManager.decodeServerConfig(desyncSearchGuid)?.let { desyncSearchGuid to it }
                ),
                fixedProfileGuid = desyncSearchGuid,
                onStartSearch = { guid, profile, family, advanced, limit, workers, progress, report, finished ->
                    runDesyncSearch(guid, profile, family, advanced, limit, workers, progress, report, finished)
                },
                onCancelSearch = ::cancelDesyncSearch,
                onApply = { guid, _, arguments ->
                    desyncProfile = PingNgCompat.PROFILE_CUSTOM
                    desyncArgs = arguments
                    applyDesyncResult(guid, arguments)
                },
                onDismiss = { showFindDesync = false },
            )
        }
    }

    private fun saveServer(
        remarks: String,
        registrationProxyGuid: String,
        endpoints: String,
        primaryEndpoint: String,
        endpointPort: String,
        deviceName: String,
        sni: String,
        dns: String,
        http2Enabled: Boolean,
        psiphon: Boolean,
        tor: Boolean,
        torBridgeMode: String,
        torBridgeSources: String,
        torBridgeLines: String,
        psiphonRegion: String,
        psiphonMode: String,
        psiphonCdnIps: String,
        psiphonCdnSni: String,
        psiphonCdnSets: String,
        desyncProfile: String,
        desyncArgs: String,
        scannedCandidates: String,
        autoScanEndpoint: Boolean,
    ) {
        if (remarks.isBlank()) {
            toast(R.string.server_lab_remarks)
            return
        }
        if (endpoints.isBlank()) {
            toast("MASQUE endpoints")
            return
        }
        val parsedEndpointPort = endpointPort.toIntOrNull()
        if (primaryEndpoint.isBlank() || parsedEndpointPort !in 1..65535) {
            toast("Invalid WARP endpoint")
            return
        }
        val guid = editGuid.ifBlank { warpSearchDraftGuid ?: UUID.randomUUID().toString() }
        val old = MmkvManager.decodeServerConfig(guid)
        val config = (old ?: ProfileItem.create(EConfigType.WARP)).apply {
            configTypeGuard()
            this.remarks = remarks.trim()
            if (!subscriptionId.isNullOrBlank()) this.subscriptionId = subscriptionId.orEmpty()
            this.description = WarpMasqueConfig.DESCRIPTION
            this.server = primaryEndpoint.trim()
            this.serverPort = parsedEndpointPort.toString()
            this.warpMasqueEndpointCandidates = endpoints.trim()
            this.warpMasquePrimaryEndpoint = primaryEndpoint.trim()
            this.warpMasqueEndpointPort = parsedEndpointPort
            this.warpMasqueEndpointMode = if (autoScanEndpoint) {
                WarpMasqueConfig.ENDPOINT_MODE_AUTO
            } else {
                WarpMasqueConfig.ENDPOINT_MODE_CUSTOM
            }
            this.autoScanEndpoint = autoScanEndpoint
            this.warpMasqueScanPreparedOnce = false
            this.warpMasqueSelectedEndpoint =
                "${com.v2ray.ang.util.Utils.getIpv6Address(primaryEndpoint.trim())}:$parsedEndpointPort"
            this.awgEndpointCandidates = scannedCandidates.ifBlank { null }
            this.warpMasqueDeviceName = deviceName.trim().ifBlank { WarpMasqueConfig.randomDeviceName() }
            this.warpMasqueSni = WarpMasqueConfig.normalizeSni(sni)
            this.warpMasqueDns = dns.trim()
            this.warpMasqueHttp2Enabled = http2Enabled
            this.finalMask = null
            this.warpMasqueSocksBind = this.warpMasqueSocksBind
                ?.trim()?.takeIf { it.isNotBlank() } ?: WarpMasqueConfig.DEFAULT_SOCKS_BIND
            this.warpMasqueSocksPort = this.warpMasqueSocksPort
                ?.coerceIn(1, 65535) ?: WarpMasqueConfig.DEFAULT_SOCKS_PORT
            this.warpMasqueConfigJson = this.warpMasqueConfigJson
                ?.trim()?.takeIf { it.isNotBlank() }
            this.psiphonEnabled = psiphon
            this.torEnabled = tor
            this.torBridgeMode = torBridgeMode.ifBlank { "auto" }.lowercase()
            this.torBridgeSources = torBridgeSources.ifBlank { "auto" }.lowercase()
            this.torBridgeLines = torBridgeLines.trim().ifBlank { null }
            this.psiphonRegion = psiphonRegion.ifBlank { "ANY" }.uppercase()
            this.psiphonMode = psiphonMode.ifBlank { "auto" }.lowercase()
            this.psiphonCdnIps = psiphonCdnIps.trim().ifBlank { null }
            this.psiphonCdnSni = psiphonCdnSni.trim().ifBlank { null }
            this.psiphonCdnSets = psiphonCdnSets.trim().ifBlank { null }
            this.pingNgProfile = desyncProfile
            this.pingNgDesyncArgs = desyncArgs.trim().ifBlank { null }
            this.warpRegistrationProxyGuid = registrationProxyGuid
        }
        if (guid.isNotBlank() && old != null && old.subscriptionId.isNotBlank()) {
            SubscriptionProfileOverrides.save(guid, old, config)
        }
        com.v2ray.ang.core.EndpointScanSettings.transfer(warpSearchDraftGuid ?: editGuid, guid)
        val saved = MmkvManager.encodeServerConfig(guid, config)
        toastSuccess(R.string.toast_success)
        ProfileEditorResult.run {
            finishSaved(saved, restartService = isRunning)
        }
    }

    private fun ProfileItem.configTypeGuard() {
        check(configType == EConfigType.WARP) { "Invalid WARP profile type" }
    }

    private fun deleteServer() {
        if (editGuid.isBlank() || isRunning) return
        MmkvManager.removeServer(editGuid)
        ProfileEditorResult.run {
            finishDeleted(editGuid)
        }
    }
}

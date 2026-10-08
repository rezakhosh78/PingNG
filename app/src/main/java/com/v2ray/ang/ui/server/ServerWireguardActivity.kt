package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.AwgWarpConfig
import com.v2ray.ang.core.AwgEndpointScanState
import com.v2ray.ang.core.AwgEndpointScanManager
import com.v2ray.ang.handler.MmkvManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import com.v2ray.ang.core.WarpAccount
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.core.WarpWireGuardConfig
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.core.EndpointScannerCli
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.FormDropdownField
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.Locale

class ServerWireguardActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.WIREGUARD

    private val isWarpWireGuard: Boolean
        get() = intent.getBooleanExtra("warpWireGuard", false) ||
            WarpWireGuardConfig.isDescription(initialConfig.description)

    private val isAwgWarp: Boolean
        get() = intent.getBooleanExtra("awgWarp", false) || AwgWarpConfig.isProfile(initialConfig)

    private val isAmneziaWg: Boolean
        get() = intent.getBooleanExtra("amneziaWg", false) ||
            isAwgWarp ||
            initialConfig.configType == EConfigType.AMNEZIAWG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("warpWireGuard", false)) {
            initialConfig.description = WarpWireGuardConfig.DESCRIPTION
            if (initialConfig.remarks.isBlank() || initialConfig.remarks.equals("WireGuard", true)) {
                initialConfig.remarks = "WARP WireGuard"
            }
        }
        if (intent.getBooleanExtra("awgWarp", false)) {
            initialConfig = initialConfig.copy(configType = EConfigType.AMNEZIAWG)
            initialConfig.description = AwgWarpConfig.DESCRIPTION
            initialConfig.amneziawgConfig = initialConfig.amneziawgConfig
                ?.takeIf(String::isNotBlank) ?: AmneziaWgFmt.EMPTY_EDIT_CONFIG
            if (initialConfig.remarks.isBlank() || initialConfig.remarks.equals("AmneziaWG", true)) {
                initialConfig.remarks = AwgWarpConfig.DEFAULT_REMARK
            }
        }
        if (intent.getBooleanExtra("amneziaWg", false)) {
            initialConfig = initialConfig.copy(configType = EConfigType.AMNEZIAWG)
            initialConfig.description = "AmneziaWG"
            if (initialConfig.amneziawgConfig.isNullOrBlank()) {
                initialConfig.amneziawgConfig = AmneziaWgFmt.EMPTY_EDIT_CONFIG
            }
            if (initialConfig.remarks.isBlank() || initialConfig.remarks.equals("AmneziaWG", true)) {
                initialConfig.remarks = "New tunnel"
            }
        }
        if (initialConfig.configType == EConfigType.AMNEZIAWG) {
            val fallbackRemark = AmneziaWgFmt.readEditableConfig(initialConfig.amneziawgConfig.orEmpty())
                ?.peers?.firstOrNull()?.get("endpoint")?.let { AmneziaWgFmt.splitEndpoint(it).first }
            initialConfig.remarks = AmneziaWgFmt.cleanRemark(initialConfig.remarks, fallbackRemark)
        }
    }

    @Composable
    override fun ScreenContent() {
        val scope = rememberCoroutineScope()
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(
                initialConfig = initialConfig
            )
        }.apply {
            configType = if (isAmneziaWg) EConfigType.AMNEZIAWG else EConfigType.WIREGUARD
        }
        val importAmneziaConfig = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val text = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                ?: error("Could not read the selected config")
                        }.removePrefix("\uFEFF")
                        val config = AmneziaWgFmt.readEditableConfig(text)
                            ?: error("The selected file does not contain Interface and Peer sections")
                        uiState.amneziawgConfig = text.trim()
                        if (isAwgWarp) {
                            uiState.awgEndpointCandidates = ""
                            uiState.awgSkipAutoScanOnce = false
                        }
                        val endpoint = config.peers.firstOrNull()?.get("endpoint").orEmpty()
                        val (host, port) = AmneziaWgFmt.splitEndpoint(endpoint)
                        if (!host.isNullOrBlank()) uiState.address = host
                        if (!port.isNullOrBlank()) uiState.port = port
                        uiState.remarks = AmneziaWgFmt.cleanRemark(config.interfaceValues["name"], host)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        toast(error.message ?: "Could not import AmneziaWG config")
                    }
                }
            }
        }

        var endpointMode by rememberSaveable {
            mutableStateOf(WarpWireGuardConfig.normalizeMode(initialConfig.warpEndpointTestMode))
        }
        var registrationProxyGuid by rememberSaveable {
            mutableStateOf(initialConfig.warpRegistrationProxyGuid ?: WarpRegistrationProxy.AUTO)
        }
        var proxyChoices by remember(editGuid) {
            mutableStateOf(WarpRegistrationProxy.choices(editGuid))
        }
        var generating by remember { mutableStateOf(false) }
        var generationJob by remember { mutableStateOf<Job?>(null) }
        var generationToken by remember { mutableStateOf(0) }
        var awgWarpGenerating by remember { mutableStateOf(false) }
        var awgWarpProgress by remember { mutableStateOf("") }
        var awgWarpSelectedEndpoint by rememberSaveable { mutableStateOf("") }
        var awgScanGuid by rememberSaveable { mutableStateOf(editGuid) }
        val awgScanState by AwgEndpointScanState.state.collectAsStateWithLifecycle()
        val awgCandidates = remember(uiState.awgEndpointCandidates) {
            EndpointScannerCli.decodeCandidates(uiState.awgEndpointCandidates)
        }

        fun recordAwgWarpProgress(line: String) {
            val raw = line.trim()
            val lower = raw.lowercase()
            if (raw.isBlank() || lower.contains("scan -p") || lower == "-p" ||
                lower.contains(" -p ") || lower.contains("github.com/") ||
                lower.startsWith("panic:") || lower.startsWith("goroutine ") ||
                lower.startsWith("main.") || lower.startsWith("runtime.")) return
            val text = raw.take(180)
            if (text.isBlank()) return
            awgWarpProgress = text
        }
        val proxyLabel = if (registrationProxyGuid == WarpRegistrationProxy.AUTO) {
            WarpRegistrationProxy.AUTO_LABEL
        } else {
            proxyChoices.firstOrNull { it.guid == registrationProxyGuid }?.label
                ?: WarpRegistrationProxy.AUTO_LABEL
        }
        var finalMaskFields by remember {
            mutableStateOf(
                WarpFinalMaskState.fromJson(
                    uiState.finalMask.ifBlank { WarpPlusConfig.DEFAULT_FINAL_MASK }
                )
            )
        }
        var showFinalMaskSearch by rememberSaveable { mutableStateOf(false) }
        fun clearWarpAccount() {
            uiState.secretKey = ""
            uiState.publicKey = ""
            uiState.address = ""
            uiState.port = ""
            uiState.localAddress = ""
            uiState.reserved = ""
            uiState.mtu = ""
        }

        fun startWarpGeneration(proxyGuid: String) {
            generationJob?.cancel()
            val token = generationToken + 1
            generationToken = token
            generating = true
            generationJob = scope.launch {
                try {
                    val account = WarpRegistrationProxy.register(
                        this@ServerWireguardActivity,
                        proxyGuid,
                        editGuid,
                    )
                    if (generationToken != token) return@launch
                    proxyChoices = WarpRegistrationProxy.choices(editGuid)
                    uiState.secretKey = account.privateKey
                    uiState.publicKey = account.peerPublicKey
                    uiState.address = account.endpointHost
                    uiState.port = account.endpointPort.toString()
                    uiState.localAddress = account.localAddress
                    uiState.reserved = account.reserved
                    uiState.mtu = account.mtu.toString()
                    uiState.awgEndpointCandidates = ""
                    uiState.awgSkipAutoScanOnce = false
                    toastSuccess(R.string.toast_success)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (generationToken == token) {
                        proxyChoices = WarpRegistrationProxy.choices(editGuid)
                        toast(error.message ?: "WARP registration failed")
                    }
                } finally {
                    if (generationToken == token) generating = false
                }
            }
        }

        fun accountFromAwgConfig(): WarpAccount? = EndpointScannerCli.accountFromConfig(
            uiState.amneziawgConfig,
            uiState.reserved,
        )

        fun createAwgWarpConfig() {
            if (awgWarpGenerating) return
            AwgEndpointScanState.clear(editGuid)
            awgWarpGenerating = true
            recordAwgWarpProgress("Registering a WARP account…")
            scope.launch {
                try {
                    val account = WarpRegistrationProxy.register(
                        this@ServerWireguardActivity,
                        registrationProxyGuid,
                        editGuid,
                    )
                    val defaultEndpoint = AwgWarpConfig.DEFAULT_ENDPOINT
                    uiState.amneziawgConfig = AwgWarpConfig.render(account, defaultEndpoint)
                    uiState.remarks = AwgWarpConfig.DEFAULT_REMARK
                    uiState.secretKey = account.privateKey
                    uiState.publicKey = account.peerPublicKey
                    val (defaultHost, defaultPort) = AmneziaWgFmt.splitEndpoint(defaultEndpoint)
                    uiState.address = defaultHost.orEmpty()
                    uiState.port = defaultPort.orEmpty()
                    uiState.localAddress = account.localAddress
                    uiState.reserved = account.reserved
                    uiState.mtu = account.mtu.toString()
                    recordAwgWarpProgress("WARP AWG config created. Use Scan Endpoint to find a faster endpoint.")
                    proxyChoices = WarpRegistrationProxy.choices(editGuid)
                    toastSuccess(R.string.toast_success)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    recordAwgWarpProgress(error.message ?: "WARP AWG generation failed")
                    toast(awgWarpProgress)
                } finally {
                    awgWarpGenerating = false
                }
            }
        }

        fun scanAwgWarpConfig() {
            if (awgWarpGenerating || AwgEndpointScanManager.isRunning) return
            if (accountFromAwgConfig() == null) {
                toast("Create a WARP AWG config before scanning")
                return
            }
            val guid = saveDraftForDesyncSearch(uiState) ?: return
            val profile = MmkvManager.decodeServerConfig(guid) ?: return
            awgScanGuid = guid
            AwgEndpointScanManager.start(this@ServerWireguardActivity, guid, profile, true)
        }

        LaunchedEffect(awgScanState.running, awgScanState.selectedEndpoint, awgScanGuid) {
            if (awgScanState.guid == awgScanGuid && !awgScanState.running && awgScanState.selectedEndpoint.isNotBlank()) {
                MmkvManager.decodeServerConfig(awgScanGuid)?.let { saved ->
                    uiState.amneziawgConfig = saved.amneziawgConfig.orEmpty()
                    uiState.address = saved.server.orEmpty()
                    uiState.port = saved.serverPort.orEmpty()
                    uiState.awgEndpointCandidates = saved.awgEndpointCandidates.orEmpty()
                    uiState.awgSkipAutoScanOnce = true
                    awgWarpSelectedEndpoint = awgScanState.selectedEndpoint
                }
            }
        }

        fun selectAwgEndpoint(candidate: EndpointScannerCli.EndpointCandidate) {
            uiState.amneziawgConfig = AmneziaWgFmt.setField(
                uiState.amneziawgConfig, "Peer", "Endpoint", candidate.endpoint,
            )
            val (host, port) = AmneziaWgFmt.splitEndpoint(candidate.endpoint)
            uiState.address = host.orEmpty()
            uiState.port = port.orEmpty()
            uiState.awgSkipAutoScanOnce = true
            awgWarpSelectedEndpoint = candidate.endpoint
        }

        LaunchedEffect(isWarpWireGuard, initialConfig.secretKey) {
            if (isWarpWireGuard && uiState.finalMask.isBlank()) {
                uiState.finalMask = WarpPlusConfig.DEFAULT_FINAL_MASK
            }
            if (isWarpWireGuard && uiState.secretKey.isBlank()) {
                startWarpGeneration(registrationProxyGuid)
            }
        }
        LaunchedEffect(isAwgWarp) {
            if (isAwgWarp && AmneziaWgFmt.value(
                    uiState.amneziawgConfig,
                    "Interface",
                    "PrivateKey",
                ).isBlank()
            ) {
                createAwgWarpConfig()
            }
        }
        ServerEditorScaffold(
            title = when {
                isAwgWarp -> "WARP AWG"
                isAmneziaWg -> "AmneziaWG"
                isWarpWireGuard -> "WARP WireGuard"
                else -> serverConfigType.toString()
            },
            onSaveClick = {
                if (isAwgWarp && awgWarpGenerating) {
                    toast("Wait for WARP AWG config creation or endpoint scanning to finish")
                } else if (isAwgWarp && AmneziaWgFmt.value(
                        uiState.amneziawgConfig,
                        "Interface",
                        "PrivateKey",
                    ).isBlank()
                ) {
                    toast("Generate a WARP AWG config before saving")
                } else if (isWarpWireGuard && (generating || uiState.secretKey.isBlank())) {
                    toast(if (generating) "Wait for WARP key generation to finish" else "Generate a WARP key before saving")
                } else {
                    if (isAwgWarp) initialConfig.description = AwgWarpConfig.DESCRIPTION
                    if (isAwgWarp) initialConfig.warpRegistrationProxyGuid = registrationProxyGuid
                    if (isWarpWireGuard) {
                        initialConfig.description = WarpWireGuardConfig.DESCRIPTION
                        initialConfig.warpEndpointTestMode = endpointMode
                        initialConfig.warpRegistrationProxyGuid = registrationProxyGuid
                        if (endpointMode == WarpWireGuardConfig.ENDPOINT_MODE_CUSTOM) {
                            initialConfig.warpWireGuardSelectedEndpoint = "${uiState.address.trim()}:${uiState.port.trim()}"
                        }
                        uiState.finalMask = finalMaskFields.toJsonOrNull().orEmpty()
                    }
                    saveServer(uiState)
                }
            }
        ) {
            if (isAmneziaWg) {
                if (isAwgWarp) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto Scan Endpoint", style = MaterialTheme.typography.bodyLarge)
                                Text("Scan on every connect", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = uiState.autoScanEndpoint,
                                onCheckedChange = { uiState.autoScanEndpoint = it },
                            )
                        }
                        Button(
                            enabled = !awgWarpGenerating && !AwgEndpointScanManager.isRunning,
                            onClick = { scanAwgWarpConfig() },
                        ) {
                            Text("Scan Endpoint")
                        }
                        if (awgScanState.guid == awgScanGuid && awgScanState.running) {
                            Text(awgScanState.message, fontSize = 12.sp, maxLines = 2)
                            Text("Found: ${awgScanState.endpoints.size}", fontSize = 12.sp)
                            Column(Modifier.fillMaxWidth().height(72.dp).verticalScroll(rememberScrollState())) {
                                awgScanState.endpoints.forEach { Text(it, fontSize = 12.sp) }
                            }
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { AwgEndpointScanManager.cancel() }) { Text("Cancel Scan") }
                        }
                        if (awgCandidates.isNotEmpty()) {
                            Text("Reachable endpoints · lowest ping first", style = MaterialTheme.typography.titleSmall)
                            Column(Modifier.fillMaxWidth().height(192.dp).verticalScroll(rememberScrollState())) {
                            awgCandidates.forEachIndexed { index, candidate ->
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { selectAwgEndpoint(candidate) },
                                    enabled = !awgWarpGenerating && !AwgEndpointScanManager.isRunning,
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${index + 1}. ${candidate.endpoint}" +
                                                if (AmneziaWgFmt.value(uiState.amneziawgConfig, "Peer", "Endpoint") == candidate.endpoint) " ✓" else "",
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
                        Button(
                            enabled = !awgWarpGenerating && !AwgEndpointScanManager.isRunning,
                            onClick = {
                                // A new WARP account starts a fresh flow: create
                                // the config first, then let the user press
                                // Scan Endpoint runs discovery for the new key.
                                awgWarpSelectedEndpoint = ""
                                uiState.awgEndpointCandidates = ""
                                uiState.awgSkipAutoScanOnce = false
                                AwgEndpointScanState.clear(editGuid)
                                uiState.amneziawgConfig = AmneziaWgFmt.EMPTY_EDIT_CONFIG
                                uiState.secretKey = ""
                                uiState.publicKey = ""
                                uiState.address = ""
                                uiState.port = ""
                                uiState.localAddress = ""
                                uiState.reserved = ""
                                uiState.mtu = ""
                                createAwgWarpConfig()
                            },
                        ) {
                            Text("Generate New WARP Key")
                        }
                    }
                }
                AmneziaWgFields(uiState, beforeRemarks = if (isAwgWarp) {
                    {
                        FormDropdownField(
                            label = "Proxy",
                            value = proxyLabel,
                            options = listOf(WarpRegistrationProxy.AUTO_LABEL) + proxyChoices.map { it.label },
                            onValueChange = { selected ->
                                registrationProxyGuid = if (selected == WarpRegistrationProxy.AUTO_LABEL) {
                                    WarpRegistrationProxy.AUTO
                                } else {
                                    proxyChoices.firstOrNull { it.label == selected }?.guid
                                        ?: WarpRegistrationProxy.AUTO
                                }
                            },
                            enabled = !awgWarpGenerating && !AwgEndpointScanManager.isRunning,
                            supportingText = "Used only to generate a new WARP key",
                        )
                    }
                } else null) {
                    importAmneziaConfig.launch(arrayOf("text/*", "application/octet-stream"))
                }
            } else {
                CommonBasicFields(
                    state = uiState,
                    onEndpointChanged = {
                        if (isWarpWireGuard) endpointMode = WarpWireGuardConfig.ENDPOINT_MODE_CUSTOM
                    },
                    afterRemarks = if (isWarpWireGuard) {
                        {
                            FormDropdownField(
                                label = "Proxy",
                                value = proxyLabel,
                                options = listOf(WarpRegistrationProxy.AUTO_LABEL) + proxyChoices.map { it.label },
                                onValueChange = { selected ->
                                    val nextProxyGuid = if (selected == WarpRegistrationProxy.AUTO_LABEL) {
                                        WarpRegistrationProxy.AUTO
                                    } else {
                                        proxyChoices.firstOrNull { it.label == selected }?.guid
                                            ?: WarpRegistrationProxy.AUTO
                                    }
                                    if (nextProxyGuid != registrationProxyGuid) {
                                        registrationProxyGuid = nextProxyGuid
                                        clearWarpAccount()
                                        startWarpGeneration(nextProxyGuid)
                                    }
                                },
                            )
                        }
                    } else null,
                )
                WireguardProtocolFields(uiState, showRawFinalMask = !isWarpWireGuard)
            }
            if (isWarpWireGuard) {
                Button(
                    enabled = !generating,
                    modifier = Modifier.padding(start = 16.dp),
                    onClick = {
                        clearWarpAccount()
                        startWarpGeneration(registrationProxyGuid)
                    },
                ) { Text(if (generating) "Generating…" else "Generate New WARP") }
                FormDropdownField(
                    label = "WARP endpoint scan mode",
                    value = endpointMode,
                    options = listOf(
                        WarpWireGuardConfig.ENDPOINT_MODE_FAST,
                        WarpWireGuardConfig.ENDPOINT_MODE_MEDIUM,
                        WarpWireGuardConfig.ENDPOINT_MODE_ALL,
                        WarpWireGuardConfig.ENDPOINT_MODE_CUSTOM,
                    ),
                    onValueChange = { endpointMode = it },
                )
                WarpFinalMaskFields(
                    state = finalMaskFields,
                    onStateChange = { state ->
                        finalMaskFields = state
                        uiState.finalMask = state.toJsonOrNull().orEmpty()
                    },
                    onFind = { showFinalMaskSearch = true },
                    onReset = {
                        finalMaskFields = WarpFinalMaskState.fromJson(WarpPlusConfig.DEFAULT_FINAL_MASK)
                        uiState.finalMask = finalMaskFields.toJsonOrNull().orEmpty()
                    },
                )
            }
            if (isAmneziaWg) PsiphonFields(
                uiState,
                title = "Psiphon Over AmneziaWG",
                hintResId = R.string.pingng_psiphon_awg_hint,
            )
            else PsiphonFields(uiState)
        }
        if (isWarpWireGuard && showFinalMaskSearch) {
            FinalMaskSearchDialog(
                guid = editGuid,
                candidateSource = { warpFinalMaskCandidates() },
                onSearch = { candidates, onResult, onProgress, onFinished ->
                    runFinalMaskSearch(uiState.toProfileItem(initialConfig), candidates, onResult, onProgress, onFinished)
                },
                onCancelSearch = { finalMaskSearchJob?.cancel() },
                onApply = {
                    finalMaskFields = WarpFinalMaskState.fromJson(it)
                    uiState.finalMask = finalMaskFields.toJsonOrNull().orEmpty()
                },
                onDismiss = { showFinalMaskSearch = false },
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.configType != EConfigType.AMNEZIAWG) return true
        return try {
            AmneziaWgFmt.toGoUapi(config.amneziawgConfig.orEmpty()) { emptyList() }
            true
        } catch (error: Exception) {
            toast("AmneziaWG configuration is invalid: ${error.message.orEmpty()}")
            false
        }
    }

    @Composable
    private fun AmneziaWgFields(
        state: ServerUiState,
        beforeRemarks: (@Composable () -> Unit)? = null,
        onAddConfig: () -> Unit,
    ) {
        Button(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            onClick = onAddConfig,
        ) { Text("Add Config") }
        beforeRemarks?.invoke()
        FormTextField(
            stringResource(R.string.server_lab_remarks),
            state.remarks,
            { state.remarks = it },
        )
        val config = remember(state.amneziawgConfig) {
            AmneziaWgFmt.readEditableConfig(state.amneziawgConfig)
        }
        if (config == null) {
            FormTextField(
                label = "AmneziaWG configuration",
                value = state.amneziawgConfig,
                onValueChange = { state.amneziawgConfig = it },
                maxLines = 10,
            )
            return
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Interface", modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            AmneziaWgFmt.editableInterfaceKeys.forEach { key ->
                AmneziaWgField(
                    key = key,
                    value = config.interfaceValues[key.lowercase(Locale.ROOT)].orEmpty(),
                    onValueChange = { value ->
                        state.amneziawgConfig = AmneziaWgFmt.setField(
                            state.amneziawgConfig, "Interface", key, value,
                        )
                        if (isAwgWarp && key.equals("PrivateKey", ignoreCase = true)) {
                            state.awgEndpointCandidates = ""
                            state.awgSkipAutoScanOnce = false
                        }
                    },
                )
            }
            config.peers.forEachIndexed { peerIndex, peer ->
                Text("Peer ${peerIndex + 1}", modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                AmneziaWgFmt.editablePeerKeys.forEach { key ->
                    AmneziaWgField(
                        key = key,
                        value = peer[key.lowercase(Locale.ROOT)].orEmpty(),
                        onValueChange = { value ->
                            state.amneziawgConfig = AmneziaWgFmt.setField(
                                state.amneziawgConfig, "Peer", key, value, peerIndex,
                            )
                            if (isAwgWarp && key.equals("PublicKey", ignoreCase = true)) {
                                state.awgEndpointCandidates = ""
                                state.awgSkipAutoScanOnce = false
                            }
                            if (peerIndex == 0 && key.equals("Endpoint", ignoreCase = true)) {
                                val (host, port) = AmneziaWgFmt.splitEndpoint(value)
                                state.address = host.orEmpty()
                                state.port = port.orEmpty()
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun AmneziaWgField(key: String, value: String, onValueChange: (String) -> Unit) {
        when {
            AmneziaWgFmt.isBooleanField(key) -> FormDropdownField(
                label = key,
                value = value,
                options = (listOf("", "true", "false") + value).distinct(),
                onValueChange = onValueChange,
            )
            else -> FormTextField(
                label = key,
                value = value,
                onValueChange = onValueChange,
                keyboardType = if (AmneziaWgFmt.isNumericField(key)) KeyboardType.Number else KeyboardType.Text,
                maxLines = if (key.startsWith("I", ignoreCase = true)) 2 else 1,
            )
        }
    }

    @Composable
    private fun WireguardProtocolFields(state: ServerUiState, showRawFinalMask: Boolean) {
        FormTextField(
            stringResource(R.string.server_lab_secret_key),
            state.secretKey,
            { state.secretKey = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_public_key),
            state.publicKey,
            { state.publicKey = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_preshared_key),
            state.preSharedKey,
            { state.preSharedKey = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_reserved),
            state.reserved,
            { state.reserved = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_local_address),
            state.localAddress,
            { state.localAddress = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_local_mtu),
            state.mtu,
            { state.mtu = it },
            keyboardType = KeyboardType.Number
        )

        if (showRawFinalMask) {
            FormTextField(
                stringResource(R.string.server_lab_final_mask),
                state.finalMask,
                { state.finalMask = it }
            )
        }
    }
}

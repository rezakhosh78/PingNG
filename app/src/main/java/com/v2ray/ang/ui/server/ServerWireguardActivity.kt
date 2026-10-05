package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.core.WarpWireGuardConfig
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.FormDropdownField
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

    private val isAmneziaWg: Boolean
        get() = intent.getBooleanExtra("amneziaWg", false) ||
            initialConfig.configType == EConfigType.AMNEZIAWG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("warpWireGuard", false)) {
            initialConfig.description = WarpWireGuardConfig.DESCRIPTION
            if (initialConfig.remarks.isBlank() || initialConfig.remarks.equals("WireGuard", true)) {
                initialConfig.remarks = "WARP WireGuard"
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

        LaunchedEffect(isWarpWireGuard, initialConfig.secretKey) {
            if (isWarpWireGuard && uiState.finalMask.isBlank()) {
                uiState.finalMask = WarpPlusConfig.DEFAULT_FINAL_MASK
            }
            if (isWarpWireGuard && uiState.secretKey.isBlank()) {
                startWarpGeneration(registrationProxyGuid)
            }
        }
        ServerEditorScaffold(
            title = when {
                isAmneziaWg -> "AmneziaWG"
                isWarpWireGuard -> "WARP WireGuard"
                else -> serverConfigType.toString()
            },
            onSaveClick = {
                if (isWarpWireGuard && (generating || uiState.secretKey.isBlank())) {
                    toast(if (generating) "Wait for WARP key generation to finish" else "Generate a WARP key before saving")
                } else {
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
                AmneziaWgFields(uiState) {
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
    private fun AmneziaWgFields(state: ServerUiState, onAddConfig: () -> Unit) {
        Button(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            onClick = onAddConfig,
        ) { Text("Add Config") }
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

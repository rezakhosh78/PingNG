package com.v2ray.ang.ui.server

import android.os.Bundle
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
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.FormDropdownField
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

class ServerWireguardActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.WIREGUARD

    private val isWarpWireGuard: Boolean
        get() = intent.getBooleanExtra("warpWireGuard", false) ||
            WarpWireGuardConfig.isDescription(initialConfig.description)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("warpWireGuard", false)) {
            initialConfig.description = WarpWireGuardConfig.DESCRIPTION
            if (initialConfig.remarks.isBlank() || initialConfig.remarks.equals("WireGuard", true)) {
                initialConfig.remarks = "WARP WireGuard"
            }
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
            configType = EConfigType.WIREGUARD
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
            title = if (isWarpWireGuard) "WARP WireGuard" else serverConfigType.toString(),
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
                                    if (isWarpWireGuard) {
                                        clearWarpAccount()
                                        startWarpGeneration(nextProxyGuid)
                                    }
                                }
                            },
                        )
                    }
                } else null,
            )
            WireguardProtocolFields(uiState, showRawFinalMask = !isWarpWireGuard)
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
            PsiphonFields(uiState)
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

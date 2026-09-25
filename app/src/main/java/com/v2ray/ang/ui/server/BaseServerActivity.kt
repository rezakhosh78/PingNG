package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.TLS
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.PingNgDesyncTuner
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.CertificateFingerprintManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.SubscriptionProfileOverrides
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.ui.main.DesyncSearchHistory
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

abstract class BaseServerActivity : BaseComponentActivity() {

    protected abstract val serverConfigType: EConfigType

    protected val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    protected val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false)
                && editGuid.isNotEmpty()
                && editGuid == MmkvManager.getSelectServer()
    }
    protected val subscriptionId by lazy { intent.getStringExtra("subscriptionId") }

    protected lateinit var initialConfig: ProfileItem
    protected var finalMaskSearchJob: Job? = null
    private var desyncSearchJob: Job? = null
    private var pendingDesyncApply: Pair<String, String>? = null
    /** GUID created for a new editor draft while an in-place search is running. */
    private var draftGuidForDesyncSearch: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existingConfig = MmkvManager.decodeServerConfig(editGuid)
        initialConfig = existingConfig ?: ProfileItem.create(serverConfigType)
    }

    @Composable
    protected fun rememberFieldOptions(): FieldOptions =
        FieldOptions(
            networkOptions = stringArrayResource(R.array.networks).toList(),
            tcpHeaderOptions = stringArrayResource(R.array.header_type_tcp).toList(),
            kcpHeaderOptions = stringArrayResource(R.array.header_type_kcp_and_quic).toList(),
            grpcModeOptions = stringArrayResource(R.array.mode_type_grpc).toList(),
            xhttpModeOptions = stringArrayResource(R.array.xhttp_mode).toList(),
            streamSecurityOptions = stringArrayResource(R.array.streamsecurityxs).toList(),
            uTlsOptions = stringArrayResource(R.array.streamsecurity_utls).toList(),
            alpnOptions = stringArrayResource(R.array.streamsecurity_alpn).toList(),
            browserDialerOptions = stringArrayResource(R.array.browser_dialer_mode_value).toList()
        )

    data class FieldOptions(
        val networkOptions: List<String>,
        val tcpHeaderOptions: List<String>,
        val kcpHeaderOptions: List<String>,
        val grpcModeOptions: List<String>,
        val xhttpModeOptions: List<String>,
        val streamSecurityOptions: List<String>,
        val uTlsOptions: List<String>,
        val alpnOptions: List<String>,
        val browserDialerOptions: List<String>
    )

    @Composable
    protected fun CommonBasicFields(
        state: ServerUiState,
        onEndpointChanged: (() -> Unit)? = null,
        afterRemarks: (@Composable () -> Unit)? = null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormTextField(
                stringResource(R.string.server_lab_remarks),
                state.remarks,
                { state.remarks = it }
            )
            afterRemarks?.invoke()
            FormTextField(
                stringResource(R.string.server_lab_address),
                state.address,
                { state.address = it; onEndpointChanged?.invoke() }
            )
            FormTextField(
                stringResource(R.string.server_lab_port),
                state.port,
                { state.port = it; onEndpointChanged?.invoke() },
                keyboardType = KeyboardType.Number
            )
        }
    }

    @Composable
    protected fun CommonNetworkFields(
        state: ServerUiState,
        options: FieldOptions
    ) {
        var showFinalMaskSearch by rememberSaveable { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormDropdownField(
                stringResource(R.string.server_lab_network),
                state.network,
                options.networkOptions,
                { state.network = it }
            )

            val headerOptions = when (state.network) {
                NetworkType.TCP.type -> options.tcpHeaderOptions
                NetworkType.KCP.type -> options.kcpHeaderOptions
                NetworkType.GRPC.type -> options.grpcModeOptions
                NetworkType.XHTTP.type -> options.xhttpModeOptions
                else -> listOf("---")
            }
            if (headerOptions.size > 1) {
                FormDropdownField(
                    stringResource(
                        when (state.network) {
                            NetworkType.GRPC.type -> R.string.server_lab_mode_type
                            NetworkType.XHTTP.type -> R.string.server_lab_xhttp_mode
                            else -> R.string.server_lab_head_type
                        }
                    ),
                    when (state.network) {
                        NetworkType.GRPC.type -> state.mode
                        NetworkType.XHTTP.type -> state.xhttpMode
                        else -> state.headerType
                    },
                    headerOptions,
                    {
                        when (state.network) {
                            NetworkType.GRPC.type -> state.mode = it
                            NetworkType.XHTTP.type -> state.xhttpMode = it
                            else -> state.headerType = it
                        }
                    }
                )
            }

            FormTextField(
                stringResource(
                    when (state.network) {
                        NetworkType.TCP.type,
                        NetworkType.HTTP_UPGRADE.type,
                        NetworkType.XHTTP.type,
                        NetworkType.H2.type -> R.string.server_lab_request_host_http

                        NetworkType.WS.type -> R.string.server_lab_request_host_ws
                        NetworkType.GRPC.type -> R.string.server_lab_request_host_grpc
                        else -> R.string.server_lab_request_host6
                    }
                ),
                if (state.network == NetworkType.GRPC.type) state.authority else state.host,
                { if (state.network == NetworkType.GRPC.type) state.authority = it else state.host = it }
            )

            if (state.network != NetworkType.KCP.type) {
                FormTextField(
                    stringResource(
                        when (state.network) {
                            NetworkType.WS.type -> R.string.server_lab_path_ws
                            NetworkType.HTTP_UPGRADE.type -> R.string.server_lab_path_httpupgrade
                            NetworkType.XHTTP.type -> R.string.server_lab_path_xhttp
                            NetworkType.H2.type -> R.string.server_lab_path_h2
                            NetworkType.GRPC.type -> R.string.server_lab_path_grpc
                            else -> R.string.server_lab_path
                        }
                    ),
                    if (state.network == NetworkType.GRPC.type) state.serviceName else state.path,
                    { if (state.network == NetworkType.GRPC.type) state.serviceName = it else state.path = it }
                )
            }

            if (state.network == NetworkType.XHTTP.type) {
                FormTextField(
                    stringResource(R.string.server_lab_xhttp_extra),
                    state.xhttpExtra,
                    { state.xhttpExtra = it }
                )
            }
            if (state.network == NetworkType.KCP.type) {
                FormTextField(
                    stringResource(R.string.server_lab_path_kcp),
                    state.seed,
                    { state.seed = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_kcp_mtu),
                    state.kcpMtu,
                    { state.kcpMtu = it },
                    keyboardType = KeyboardType.Number
                )
                FormTextField(
                    stringResource(R.string.server_lab_kcp_tti),
                    state.kcpTti,
                    { state.kcpTti = it },
                    keyboardType = KeyboardType.Number
                )
            }
            FormTextField(
                stringResource(R.string.server_lab_final_mask),
                state.finalMask,
                { state.finalMask = it }
            )
            Button(
                modifier = Modifier.padding(start = 16.dp),
                onClick = { showFinalMaskSearch = true },
            ) { Text("Find Final Mask Setting") }
            if (state.network == NetworkType.WS.type || state.network == NetworkType.XHTTP.type) {
                FormDropdownField(
                    stringResource(R.string.server_lab_browser_dialer),
                    state.browserDialerMode,
                    options.browserDialerOptions,
                    { state.browserDialerMode = it }
                )
            }
        }
        if (showFinalMaskSearch) {
            FinalMaskSearchDialog(
                guid = editGuid,
                onSearch = { candidates, onResult, onProgress, onFinished ->
                    runFinalMaskSearch(state.toProfileItem(initialConfig), candidates, onResult, onProgress, onFinished)
                },
                onCancelSearch = { finalMaskSearchJob?.cancel() },
                onApply = { state.finalMask = it },
                onDismiss = { showFinalMaskSearch = false },
            )
        }
    }

    protected fun runFinalMaskSearch(
        config: ProfileItem,
        candidates: List<FinalMaskCandidate>,
        onResult: (FinalMaskCandidate, Long) -> Unit,
        onProgress: (Int) -> Unit,
        onFinished: () -> Unit,
    ) {
        val guid = editGuid.takeIf { it.isNotBlank() } ?: return onFinished()
        finalMaskSearchJob = (application as com.v2ray.ang.AngApplication).applicationScope.launch(Dispatchers.IO) {
            val original = MmkvManager.decodeServerConfig(guid) ?: config
            try {
                withContext(Dispatchers.Main) { LauncherManager.stopService(this@BaseServerActivity) }
                kotlinx.coroutines.delay(700L)
                candidates.forEachIndexed { index, candidate ->
                    try {
                        MmkvManager.encodeServerConfig(guid, original.copy(finalMask = candidate.json))
                        withContext(Dispatchers.Main) {
                            if (index > 0) {
                                LauncherManager.stopService(this@BaseServerActivity)
                                kotlinx.coroutines.delay(350L)
                            }
                            LauncherManager.startProxyOnlyService(this@BaseServerActivity, guid)
                        }
                        kotlinx.coroutines.delay(if (index == 0) 1200L else 900L)
                        val delay = SpeedtestManager.liveTunnelDelay(
                            SettingsManager.getDelayTestUrl(),
                            timeoutMs = 2500,
                        )
                        if (delay >= 0L) withContext(Dispatchers.Main) { onResult(candidate, delay) }
                    } finally {
                        withContext(Dispatchers.Main) { onProgress(index + 1) }
                    }
                }
            } catch (_: CancellationException) {
                // Partial results remain visible in the dialog.
            } finally {
                MmkvManager.encodeServerConfig(guid, original)
                withContext(NonCancellable + Dispatchers.Main) {
                    LauncherManager.stopService(this@BaseServerActivity)
                    if (isRunning) LauncherManager.startService(this@BaseServerActivity, guid)
                    onFinished()
                }
            }
        }.also { job -> job.invokeOnCompletion { if (finalMaskSearchJob === job) finalMaskSearchJob = null } }
    }

    @Composable
    protected fun CommonStreamSecurityFields(
        state: ServerUiState,
        options: FieldOptions,
        scope: CoroutineScope,
        buildProfileItem: () -> ProfileItem
    ) {
        val context = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormDropdownField(
                stringResource(R.string.server_lab_stream_security),
                state.streamSecurity,
                options.streamSecurityOptions,
                { state.streamSecurity = it }
            )

            if (state.streamSecurity.isBlank()) {
                return@Column
            }

            FormTextField(
                stringResource(R.string.server_lab_sni),
                state.sni,
                { state.sni = it }
            )
            FormDropdownField(
                stringResource(R.string.server_lab_stream_fingerprint),
                state.fingerPrint,
                options.uTlsOptions,
                { state.fingerPrint = it }
            )

            if (state.streamSecurity == TLS) {
                SettingsSwitchItem(
                    title = stringResource(R.string.server_lab_allow_insecure),
                    checked = state.allowInsecure,
                    onCheckedChange = { state.allowInsecure = it }
                )
                FormDropdownField(
                    stringResource(R.string.server_lab_stream_alpn),
                    state.alpn,
                    options.alpnOptions,
                    { state.alpn = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_cipher_suites),
                    state.cipherSuites,
                    { state.cipherSuites = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_ech_config_list),
                    state.echConfigList,
                    { state.echConfigList = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_verify_peer_cert_by_name),
                    state.verifyPeerCertByName,
                    { state.verifyPeerCertByName = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_pinned_ca256),
                    state.pinnedCA256,
                    { state.pinnedCA256 = it }
                )
                Button(
                    onClick = {
                        if (state.address.isBlank()) {
                            context.toast(R.string.server_lab_address)
                            return@Button
                        }
                        if (
                            state.configType != EConfigType.HYSTERIA2 &&
                            (state.port.toIntOrNull() ?: 0) <= 0
                        ) {
                            context.toast(R.string.server_lab_port)
                            return@Button
                        }
                        val temp = buildProfileItem()
                        scope.launch {
                            state.isFetchingCert = true
                            try {
                                val sha256 = withContext(Dispatchers.IO) {
                                    CertificateFingerprintManager.fetchForManualFill(temp)
                                }
                                if (sha256.isNullOrBlank()) {
                                    context.toast(R.string.toast_fetch_cert_sha256_failed)
                                } else {
                                    state.pinnedCA256 = sha256
                                    context.toastSuccess(R.string.toast_fetch_cert_sha256_success)
                                }
                            } finally {
                                state.isFetchingCert = false
                            }
                        }
                    },
                    enabled = !state.isFetchingCert,
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    Text(stringResource(R.string.pinned_ca256_action_fetch))
                }
            } else if (state.streamSecurity == REALITY) {
                FormTextField(
                    stringResource(R.string.server_lab_public_key),
                    state.publicKeyReality,
                    { state.publicKeyReality = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_short_id),
                    state.shortId,
                    { state.shortId = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_spider_x),
                    state.spiderX,
                    { state.spiderX = it }
                )
                FormTextField(
                    stringResource(R.string.server_lab_mldsa65_verify),
                    state.mldsa65Verify,
                    { state.mldsa65Verify = it }
                )
            }
        }
    }

    @Composable
    protected fun PsiphonFields(state: ServerUiState) {
        PsiphonEditorFields(
            enabled = state.psiphonEnabled,
            region = state.psiphonRegion,
            onEnabledChange = { state.psiphonEnabled = it },
            onRegionChange = { state.psiphonRegion = it },
            mode = state.psiphonMode,
            onModeChange = { state.psiphonMode = it },
            cdnIps = state.psiphonCdnIps,
            onCdnIpsChange = { state.psiphonCdnIps = it },
            cdnSni = state.psiphonCdnSni,
            onCdnSniChange = { state.psiphonCdnSni = it },
            cdnSets = state.psiphonCdnSets,
            onCdnSetsChange = { state.psiphonCdnSets = it },
        )
    }

    protected fun validateBasicConfig(state: ServerUiState): Boolean {
        if (state.remarks.isBlank()) {
            toast(R.string.server_lab_remarks)
            return false
        }
        if (state.address.isBlank()) {
            toast(R.string.server_lab_address)
            return false
        }
        if (
            state.configType != EConfigType.HYSTERIA2 &&
            (state.port.toIntOrNull() ?: 0) <= 0
        ) {
            toast(R.string.server_lab_port)
            return false
        }
        if (
            state.configType == EConfigType.VLESS &&
            state.pingNgProfile == "Custom" &&
            state.pingNgDesyncArgs.isBlank()
        ) {
            toast(R.string.pingng_custom_args_required)
            return false
        }
        return true
    }

    protected open fun validateProtocolConfig(config: ProfileItem): Boolean = true

    protected open fun validateCommonConfig(config: ProfileItem): Boolean {

        if (config.password.isNullOrBlank()) {
            if (config.configType == EConfigType.VMESS ||
                config.configType == EConfigType.VLESS
            ) {
                toast(R.string.server_lab_id)
                return false
            }

            if (config.configType == EConfigType.TROJAN ||
                config.configType == EConfigType.SHADOWSOCKS ||
                config.configType == EConfigType.HYSTERIA2
            ) {
                toast(R.string.server_lab_id3)
                return false
            }
        }

        if (
            config.configType == EConfigType.TROJAN &&
            config.security.isNullOrBlank()
        ) {
            toast(R.string.server_lab_stream_security)
            return false
        }
        if (!config.xhttpExtra.isNullOrBlank() && JsonUtil.parseString(config.xhttpExtra) == null) {
            toast(R.string.server_lab_xhttp_extra)
            return false
        }
        if (!config.finalMask.isNullOrBlank() && JsonUtil.parseString(config.finalMask) == null) {
            toast(R.string.server_lab_final_mask)
            return false
        }
        return true
    }

    /** Saves the current editor draft so an in-place search can test it. */
    protected fun saveDraftForDesyncSearch(state: ServerUiState): String? {
        if (!validateBasicConfig(state)) return null
        val config = state.toProfileItem(initialConfig)
        if (!validateCommonConfig(config)) return null
        if (!validateProtocolConfig(config)) return null

        config.description = AngConfigManager.generateDescription(config)
        // Dedicated WARP WireGuard profiles are regular WireGuard outbounds at
        // runtime, but need a durable marker so the WARP endpoint tester and
        // editor are restored after reload/import.
        if (initialConfig.description == com.v2ray.ang.core.WarpWireGuardConfig.DESCRIPTION) {
            config.description = com.v2ray.ang.core.WarpWireGuardConfig.DESCRIPTION
        }
        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId.orEmpty()
        }
        val targetGuid = draftGuidForDesyncSearch?.takeIf { it.isNotBlank() } ?: editGuid
        val savedGuid = MmkvManager.encodeServerConfig(targetGuid, config)
        // A new profile has no editGuid yet. Keep the generated key so the
        // final Save action updates the same profile instead of creating a
        // second profile and leaving the searched values behind.
        draftGuidForDesyncSearch = savedGuid
        return savedGuid
    }

    protected fun runDesyncSearch(
        guid: String,
        profile: ProfileItem,
        family: PingNgDesyncTuner.SearchFamily,
        advanced: Boolean,
        maxProfiles: Int,
        workers: Int,
        progress: (tested: Int, total: Int) -> Unit,
        report: (List<Pair<PingNgDesyncTuner.Candidate, Long>>) -> Unit,
        finished: () -> Unit,
    ) {
        if (guid.isBlank() || !PingNgCompat.supportsNativeDesync(profile)) {
            report(emptyList())
            finished()
            return
        }
        if (desyncSearchJob?.isActive == true) return

        pendingDesyncApply = null
        val original = MmkvManager.decodeServerConfig(guid) ?: profile
        val isWarpMasque = com.v2ray.ang.core.WarpMasqueConfig.isDescription(profile.description)
        val generated = PingNgDesyncTuner.generate(profile, advanced, family)
        val candidates = generated
            .take(maxProfiles.coerceIn(1, generated.size.coerceAtLeast(1)))
            .ifEmpty {
                listOf(
                    PingNgDesyncTuner.Candidate(
                        label = "default ${family.method.orEmpty()}".trim(),
                        arguments = PingNgCompat.buildCustomArguments(
                            PingNgCompat.CustomOptions(
                                method = family.method ?: PingNgCompat.METHOD_SPLIT,
                            )
                        ),
                    )
                )
            }
        progress(0, candidates.size.coerceAtLeast(1))

        desyncSearchJob = (application as com.v2ray.ang.AngApplication).applicationScope.launch(Dispatchers.IO) {
            val successful = mutableListOf<Pair<PingNgDesyncTuner.Candidate, Long>>()
            var proxyStarted = false
            try {
                withContext(Dispatchers.Main) { LauncherManager.stopService(this@BaseServerActivity) }
                kotlinx.coroutines.delay(700L)
                candidates.forEachIndexed { index, candidate ->
                    try {
                        currentCoroutineContext().ensureActive()
                        MmkvManager.encodeServerConfig(
                            guid,
                            original.copy(
                                pingNgProfile = PingNgCompat.PROFILE_CUSTOM,
                                pingNgDesyncArgs = candidate.arguments,
                            )
                        )
                        withContext(Dispatchers.Main) {
                            if (isWarpMasque && proxyStarted) {
                                // The Go MASQUE process keeps its outer TLS
                                // socket open. Reconfigure changes only the
                                // native listener, so every candidate needs
                                // a fresh real MASQUE handshake.
                                LauncherManager.stopService(this@BaseServerActivity)
                            } else if (proxyStarted) {
                                LauncherManager.reconfigureProxyOnlyService(this@BaseServerActivity, guid)
                            }
                        }
                        if (isWarpMasque && proxyStarted) kotlinx.coroutines.delay(700L)
                        if (!proxyStarted || isWarpMasque) {
                            withContext(Dispatchers.Main) {
                                LauncherManager.startProxyOnlyService(this@BaseServerActivity, guid)
                            }
                            proxyStarted = true
                        }
                        kotlinx.coroutines.delay(if (index == 0) 1200L else 850L)
                        val testUrl = if (isWarpMasque) {
                            "http://cp.cloudflare.com/generate_204"
                        } else SettingsManager.getDelayTestUrl()
                        var delayMillis = -1L
                        repeat(if (isWarpMasque) 4 else 1) { attempt ->
                            currentCoroutineContext().ensureActive()
                            if (delayMillis < 0L) {
                                if (attempt > 0) kotlinx.coroutines.delay(650L)
                                delayMillis = SpeedtestManager.liveTunnelDelay(
                                    testUrl,
                                    timeoutMs = if (isWarpMasque) 3000 else 2500,
                                )
                            }
                        }
                        if (delayMillis >= 0L) {
                            successful += candidate to delayMillis
                            DesyncSearchHistory.save(guid, listOf(candidate to delayMillis), family, advanced)
                            withContext(Dispatchers.Main) {
                                report(successful.sortedBy { it.second })
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // A malformed candidate or a transient probe failure must
                        // not terminate the whole category search.
                        LogUtil.e(
                            message = "Desync candidate ${index + 1}/${candidates.size} failed",
                            throwable = error,
                        )
                    } finally {
                        withContext(Dispatchers.Main) {
                            progress(index + 1, candidates.size.coerceAtLeast(1))
                        }
                    }
                }
                if (successful.isNotEmpty()) {
                    DesyncSearchHistory.save(guid, successful.sortedBy { it.second }, family, advanced)
                }
            } catch (_: CancellationException) {
                if (successful.isNotEmpty()) {
                    DesyncSearchHistory.save(guid, successful.sortedBy { it.second }, family, advanced)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    val pending = pendingDesyncApply
                        ?.takeIf { it.first == guid }
                        ?.second
                    if (pending != null) {
                        pendingDesyncApply = null
                        MmkvManager.encodeServerConfig(
                            guid,
                            original.copy(
                                pingNgProfile = PingNgCompat.PROFILE_CUSTOM,
                                pingNgDesyncArgs = pending,
                            )
                        )
                    } else {
                        MmkvManager.encodeServerConfig(guid, original)
                    }
                    LauncherManager.stopService(this@BaseServerActivity)
                    if (isRunning) LauncherManager.startService(this@BaseServerActivity, guid)
                    report(successful.sortedBy { it.second })
                    finished()
                }
            }
        }.also { job ->
            job.invokeOnCompletion { if (desyncSearchJob === job) desyncSearchJob = null }
        }
    }

    protected fun cancelDesyncSearch() {
        desyncSearchJob?.cancel()
    }

    /** Applies a live search result without letting search cleanup restore over it. */
    protected fun applyDesyncResult(guid: String, arguments: String) {
        val current = MmkvManager.decodeServerConfig(guid) ?: return
        val updated = current.copy(
            pingNgProfile = PingNgCompat.PROFILE_CUSTOM,
            pingNgDesyncArgs = arguments,
        )
        // Persist immediately. The search job is cancellable and its cleanup
        // can otherwise restore the original profile before the editor closes.
        if (guid == editGuid && updated.subscriptionId.isNotBlank()) {
            SubscriptionProfileOverrides.save(editGuid, initialConfig, updated)
        }
        MmkvManager.encodeServerConfig(guid, updated)
        if (desyncSearchJob?.isActive == true) {
            pendingDesyncApply = guid to arguments
            desyncSearchJob?.cancel()
        }
    }

    protected fun saveServer(state: ServerUiState): Boolean {
        if (!validateBasicConfig(state)) return false
        val config = state.toProfileItem(initialConfig)
        if (!validateCommonConfig(config)) return false
        if (!validateProtocolConfig(config)) return false

        config.description = AngConfigManager.generateDescription(config)
        // A WARP WireGuard profile is stored as a normal WireGuard outbound,
        // so keep its marker across save; MainActivity needs it to reopen the
        // WARP editor and run endpoint discovery before connecting.
        if (initialConfig.description == com.v2ray.ang.core.WarpWireGuardConfig.DESCRIPTION) {
            config.description = com.v2ray.ang.core.WarpWireGuardConfig.DESCRIPTION
            if (config.remarks.isBlank()) config.remarks = "WARP WireGuard"
        }
        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId.orEmpty()
        }
        val targetGuid = draftGuidForDesyncSearch?.takeIf { it.isNotBlank() } ?: editGuid
        if (editGuid.isNotBlank() && config.subscriptionId.isNotBlank()) {
            SubscriptionProfileOverrides.save(editGuid, initialConfig, config)
        }
        val savedGuid = MmkvManager.encodeServerConfig(targetGuid, config)
        draftGuidForDesyncSearch = null
        toastSuccess(R.string.toast_success)
        ProfileEditorResult.run {
            finishSaved(savedGuid, isRunning)
        }
        return true
    }

    @Composable
    protected fun ServerEditorScaffold(
        title: String,
        onSaveClick: () -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) {
        var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
        val scrollState = rememberScrollState()
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                AppTopBar(
                    title = title,
                    onBackClick = { finish() },
                    actions = {
                        if (editGuid.isNotEmpty() && !isRunning) {
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_delete_24dp),
                                    stringResource(R.string.acc_delete)
                                )
                            }
                        }
                        IconButton(onClick = onSaveClick) {
                            Icon(
                                painterResource(R.drawable.ic_fab_check),
                                stringResource(R.string.acc_save)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
                    .verticalScroll(scrollState)
                    .verticalScrollbar(scrollState)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
                NavigationBarsSpacer()
            }
        }
        if (showDeleteDialog) {
            DeleteConfirmDialog(
                message = stringResource(R.string.confirm_delete_profile),
                onConfirm = {
                    showDeleteDialog = false
                    deleteServer(editGuid)
                },
                onDismiss = { showDeleteDialog = false }
            )
        }
    }

    private fun deleteServer(guid: String) {
        if (guid.isEmpty() || guid == MmkvManager.getSelectServer()) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        MmkvManager.removeServer(guid)
        ProfileEditorResult.run {
            finishDeleted(guid)
        }
    }
}

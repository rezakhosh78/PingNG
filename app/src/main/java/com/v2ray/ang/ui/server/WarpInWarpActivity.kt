package com.v2ray.ang.ui.server

import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.WarpAccount
import com.v2ray.ang.core.EndpointScannerCli
import com.v2ray.ang.core.AwgEndpointScanState
import com.v2ray.ang.fmt.AmneziaWgFmt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.core.WarpShareCodec
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class WarpFinalMaskState(
    val enabled: Boolean = true,
    val resetMin: String = "30",
    val resetMax: String = "60",
    val noiseCount: String = "3",
    val randMin: String = "50",
    val randMax: String = "100",
    val rangeMin: String = "16",
    val rangeMax: String = "255",
    val delayMin: String = "1",
    val delayMax: String = "3",
) {
    fun toJsonOrNull(): String? {
        if (!enabled) return null
        val root = JsonObject()
        val settings = JsonObject()
        settings.addProperty("reset", "${resetMin.numberOr(30)}-${resetMax.numberOr(60)}")
        val noise = JsonArray()
        repeat(noiseCount.numberOr(3).coerceIn(1, 20)) {
            noise.add(JsonObject().apply {
                addProperty("rand", "${randMin.numberOr(50)}-${randMax.numberOr(100)}")
                addProperty("randRange", "${rangeMin.numberOr(16)}-${rangeMax.numberOr(255)}")
                addProperty("delay", "${delayMin.numberOr(1)}-${delayMax.numberOr(3)}")
            })
        }
        settings.add("noise", noise)
        root.add("udp", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "noise")
                add("settings", settings)
            })
        })
        return root.toString()
    }

    companion object {
        fun fromJson(raw: String): WarpFinalMaskState {
            val settings = runCatching {
                JsonUtil.parseString(raw)
                    ?.getAsJsonArray("udp")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.getAsJsonObject("settings")
            }.getOrNull() ?: return WarpFinalMaskState()
            fun range(name: String, fallback: Pair<String, String>): Pair<String, String> {
                val value = settings.get(name)?.asString.orEmpty().split("-")
                return if (value.size == 2) value[0] to value[1] else fallback
            }
            val reset = range("reset", "30" to "60")
            val noise = settings.getAsJsonArray("noise")?.firstOrNull()?.asJsonObject
            val rand = noise?.get("rand")?.asString.orEmpty().split("-")
            val randomRange = noise?.get("randRange")?.asString.orEmpty().split("-")
            val delay = noise?.get("delay")?.asString.orEmpty().split("-")
            return WarpFinalMaskState(
                resetMin = reset.first,
                resetMax = reset.second,
                noiseCount = settings.getAsJsonArray("noise")?.size()?.toString() ?: "3",
                randMin = rand.getOrNull(0) ?: "50",
                randMax = rand.getOrNull(1) ?: "100",
                rangeMin = randomRange.getOrNull(0) ?: "16",
                rangeMax = randomRange.getOrNull(1) ?: "255",
                delayMin = delay.getOrNull(0) ?: "1",
                delayMax = delay.getOrNull(1) ?: "3",
            )
        }
    }
}

private fun String.numberOr(default: Int): Int = toIntOrNull() ?: default

internal fun warpFinalMaskCandidates(): List<FinalMaskCandidate> {
    val resets = listOf("20" to "40", "30" to "60", "40" to "80")
    val counts = listOf("3", "5", "7")
    val randoms = listOf("40" to "80", "50" to "100", "60" to "120")
    val ranges = listOf("0" to "200", "0" to "255", "16" to "255")
    val delays = listOf("1" to "3", "1" to "5", "2" to "8")
    return resets.flatMap { reset ->
        counts.flatMap { count ->
            randoms.flatMap { random ->
                ranges.flatMap { range ->
                    delays.map { delay ->
                        val state = WarpFinalMaskState(
                            resetMin = reset.first,
                            resetMax = reset.second,
                            noiseCount = count,
                            randMin = random.first,
                            randMax = random.second,
                            rangeMin = range.first,
                            rangeMax = range.second,
                            delayMin = delay.first,
                            delayMax = delay.second,
                        )
                        FinalMaskCandidate(
                            title = "UDP noise reset ${reset.first}-${reset.second}, ${count} entries, delay ${delay.first}-${delay.second}",
                            json = state.toJsonOrNull().orEmpty(),
                        )
                    }
                }
            }
        }
    }.distinctBy { it.json }
}

private data class WarpLayerState(
    val server: String,
    val port: String,
    val peerPublicKey: String = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
    val privateKey: String = "",
    val localAddress: String = "172.16.0.2/32",
    val reserved: String = "0,0,0",
    val mtu: String = "1280",
    val keepAlive: String = WarpPlusConfig.DEFAULT_KEEP_ALIVE.toString(),
) {
    fun withAccount(account: WarpAccount) = copy(
        // Keep the endpoint fields user-controlled. A fresh registration
        // changes the account identity, not the tested ingress endpoint.
        peerPublicKey = account.peerPublicKey,
        privateKey = account.privateKey,
        localAddress = account.localAddress,
        reserved = account.reserved,
        mtu = account.mtu.toString(),
    )

    companion object {
        fun outerDefault() = WarpLayerState(
            server = "engage.cloudflareclient.com",
            port = "2408",
        )

        fun innerDefault() = WarpLayerState(
            server = "162.159.192.1",
            port = "2408",
        )

        fun from(profile: ProfileItem?, fallback: WarpLayerState): WarpLayerState = profile?.let {
            WarpLayerState(
                server = it.server.orEmpty().ifBlank { fallback.server },
                port = it.serverPort.orEmpty().toIntOrNull()?.takeIf { port -> port in 1..65535 }?.toString() ?: fallback.port,
                peerPublicKey = it.publicKey.orEmpty(),
                privateKey = it.secretKey.orEmpty(),
                localAddress = it.localAddress.orEmpty().ifBlank { fallback.localAddress },
                reserved = it.reserved.orEmpty().ifBlank { fallback.reserved },
                mtu = it.mtu?.toString() ?: fallback.mtu,
                keepAlive = it.warpKeepAlive?.toString() ?: fallback.keepAlive,
            )
        } ?: fallback
    }
}

class WarpInWarpActivity : BaseComponentActivity() {
    private var warpPlusDraftGuid: String? = null
    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val subscriptionId by lazy { intent.getStringExtra("subscriptionId").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false) && editGuid.isNotBlank() &&
            editGuid == MmkvManager.getSelectServer()
    }

    private lateinit var initialRemarks: String
    private lateinit var initialOuter: WarpLayerState
    private lateinit var initialInner: WarpLayerState
    private var initialFinalMask = ""
    private var initialCustomFinalMask = ""
    private var initialFastFinalMask = ""
    private var initialAllFinalMask = ""
    private var initialFinalMaskEnabled = true
    private var initialDesync = PingNgCompat.PROFILE_OFF
    private var initialDesyncArgs = ""
    private var initialPsiphon = false
    private var initialTor = false
    private var initialTorBridgeMode = "auto"
    private var initialTorBridgeSources = "auto"
    private var initialTorBridgeLines = ""
    private var initialRegion = "ANY"
    private var initialPsiphonMode = "auto"
    private var initialPsiphonCdnIps = ""
    private var initialPsiphonCdnSni = ""
    private var initialPsiphonCdnSets = ""
    private var initialEndpointTestMode = WarpPlusConfig.ENDPOINT_MODE_AUTO
    private var initialAutoScanEndpoint = true
    private var initialRegistrationProxyGuid = WarpRegistrationProxy.AUTO
    private var initialChildGuids = emptyList<String>()
    private var initialOuterGuid: String? = null
    private var initialInnerGuid: String? = null
    private var finalMaskSearchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parent = MmkvManager.decodeServerConfig(editGuid)
        // New WARP Plus profiles start with a useful editable remark. Existing
        // user-defined remarks remain unchanged.
        initialRemarks = parent?.remarks.orEmpty().ifBlank { "WARP IN WARP" }
        // Xray's WARP chain is stored as inner,outer: the inner WARP carries
        // user traffic and dials the outer WARP through dialerProxy. FinalMask
        // belongs to the outer transport hop.
        initialDesync = parent?.pingNgProfile.orEmpty().ifBlank { PingNgCompat.PROFILE_OFF }
        initialDesyncArgs = parent?.pingNgDesyncArgs.orEmpty()
        initialPsiphon = parent?.psiphonEnabled == true
        initialTor = parent?.torEnabled == true
        initialTorBridgeMode = parent?.torBridgeMode.orEmpty().ifBlank { "auto" }
        initialTorBridgeSources = parent?.torBridgeSources.orEmpty().ifBlank { "auto" }
        initialTorBridgeLines = parent?.torBridgeLines.orEmpty()
        initialRegion = parent?.psiphonRegion.orEmpty().ifBlank { "ANY" }
        initialPsiphonMode = parent?.psiphonMode.orEmpty().ifBlank { "auto" }
        initialPsiphonCdnIps = parent?.psiphonCdnIps.orEmpty()
        initialPsiphonCdnSni = parent?.psiphonCdnSni.orEmpty()
        initialPsiphonCdnSets = parent?.psiphonCdnSets.orEmpty()
        initialEndpointTestMode = WarpPlusConfig.normalizeEndpointTestMode(parent?.warpEndpointTestMode)
        initialAutoScanEndpoint = parent?.autoScanEndpoint
            ?: (initialEndpointTestMode != WarpPlusConfig.ENDPOINT_MODE_CUSTOM)
        initialRegistrationProxyGuid = WarpRegistrationProxy.normalizeSelection(parent?.warpRegistrationProxyGuid)
        val childRemarks = parent?.proxyChainProfiles.orEmpty().split(",")
            .map(String::trim).filter(String::isNotBlank)
        val children = childRemarks.mapNotNull { remark ->
            SettingsManager.getServerViaRemarks(remark)?.let { profile ->
                val guid = MmkvManager.decodeAllServerList().firstOrNull { candidate ->
                    MmkvManager.decodeServerConfig(candidate)?.remarks == profile.remarks
                }
                guid to profile
            }
        }
        initialChildGuids = children.mapNotNull { it.first }
        // Profiles created by the previous Pi37 build used outer,inner. Keep
        // those editable while storing all newly saved chains as inner,outer.
        val legacyOuterFirst = parent?.description == WarpPlusConfig.LEGACY_DESCRIPTION
        val outerIndex = if (legacyOuterFirst) 0 else 1
        val innerIndex = if (legacyOuterFirst) 1 else 0
        val storedOuterFinalMask = children.getOrNull(outerIndex)?.second?.finalMask.orEmpty()
        initialCustomFinalMask = storedOuterFinalMask.ifBlank { WarpPlusConfig.DEFAULT_FINAL_MASK }
        initialFastFinalMask = parent?.warpFastFinalMask.orEmpty()
        initialAllFinalMask = parent?.warpAllFinalMask.orEmpty()
        initialFinalMask = if (initialAutoScanEndpoint) {
            initialFastFinalMask.ifBlank { initialAllFinalMask.ifBlank { WarpPlusConfig.DEFAULT_FINAL_MASK } }
        } else {
            initialCustomFinalMask
        }
        initialFinalMaskEnabled = parent?.warpFinalMaskEnabled ?: true
        initialOuterGuid = children.getOrNull(outerIndex)?.first
        initialInnerGuid = children.getOrNull(innerIndex)?.first
        val outerFallback = WarpLayerState.outerDefault()
        val innerFallback = WarpLayerState.innerDefault()
        initialOuter = WarpLayerState.from(children.getOrNull(outerIndex)?.second, outerFallback)
        initialInner = WarpLayerState.from(children.getOrNull(innerIndex)?.second, innerFallback)
    }

    private fun findChainChild(parent: ProfileItem, index: Int): ProfileItem? =
        parent.proxyChainProfiles.orEmpty().split(",").map(String::trim)
            .filter(String::isNotBlank).getOrNull(index)
            ?.let { SettingsManager.getServerViaRemarks(it) }

    @Composable
    override fun ScreenContent() {
        val scope = rememberCoroutineScope()
        var remarks by remember { mutableStateOf(initialRemarks) }
        var outer by remember { mutableStateOf(initialOuter) }
        var inner by remember { mutableStateOf(initialInner) }
        var finalMask by remember {
            mutableStateOf(WarpFinalMaskState.fromJson(initialFinalMask).copy(enabled = initialFinalMaskEnabled))
        }
        var fastFinalMaskJson by remember { mutableStateOf(initialFastFinalMask) }
        var allFinalMaskJson by remember { mutableStateOf(initialAllFinalMask) }
        var customFinalMaskJson by remember { mutableStateOf(initialCustomFinalMask) }
        var psiphon by remember { mutableStateOf(initialPsiphon) }
        var tor by remember { mutableStateOf(initialTor) }
        var torBridgeMode by remember { mutableStateOf(initialTorBridgeMode) }
        var torBridgeSources by remember { mutableStateOf(initialTorBridgeSources) }
        var torBridgeLines by remember { mutableStateOf(initialTorBridgeLines) }
        var region by remember { mutableStateOf(initialRegion) }
        var psiphonMode by remember { mutableStateOf(initialPsiphonMode) }
        var psiphonCdnIps by remember { mutableStateOf(initialPsiphonCdnIps) }
        var psiphonCdnSni by remember { mutableStateOf(initialPsiphonCdnSni) }
        var psiphonCdnSets by remember { mutableStateOf(initialPsiphonCdnSets) }
        var autoScanEndpoint by remember { mutableStateOf(initialAutoScanEndpoint) }
        val scanGuid = rememberSaveable(editGuid) {
            editGuid.ifBlank {
                warpPlusDraftGuid ?: UUID.randomUUID().toString().also { warpPlusDraftGuid = it }
            }
        }
        warpPlusDraftGuid = scanGuid
        val scanState by AwgEndpointScanState.state.collectAsStateWithLifecycle()
        var scanJob by remember { mutableStateOf<Job?>(null) }
        var outerCandidates by remember { mutableStateOf(
            editGuid.takeIf { it.isNotBlank() }?.let(MmkvManager::decodeServerConfig)
                ?.awgEndpointCandidates.orEmpty(),
        ) }
        // Both hops use the same scanned pool. Restore it when reopening the
        // editor so a previous scan stays visible and selectable.
        var innerCandidates by remember { mutableStateOf(outerCandidates) }
        var registrationProxyGuid by remember {
            mutableStateOf(WarpRegistrationProxy.normalizeSelection(initialRegistrationProxyGuid))
        }
        var proxyChoices by remember(editGuid) { mutableStateOf(WarpRegistrationProxy.choices(editGuid)) }
        val proxyLabel = if (WarpRegistrationProxy.isAutoChoice(registrationProxyGuid)) {
            WarpRegistrationProxy.AUTO_LABEL
        } else {
            proxyChoices.firstOrNull { it.guid == registrationProxyGuid }?.label
                ?: WarpRegistrationProxy.AUTO_LABEL
        }
        var generating by remember { mutableStateOf(false) }
        var generationJob by remember { mutableStateOf<Job?>(null) }
        var generationToken by remember { mutableStateOf(0) }
        var showFinalMaskFinder by remember { mutableStateOf(false) }
        var showDelete by rememberSaveable { mutableStateOf(false) }
        val lazyListState = rememberLazyListState()

        fun cancelEndpointWork() {
            generationToken += 1
            generationJob?.cancel()
            generationJob = null
            generating = false
            scanJob?.cancel()
            scanJob = null
            EndpointScannerCli.cancelActiveScans()
            if (AwgEndpointScanState.state.value.guid == scanGuid) {
                AwgEndpointScanState.clear(scanGuid)
            }
        }

        fun startWarpGeneration(proxyGuid: String) {
            cancelEndpointWork()
            outer = outer.copy(privateKey = "")
            inner = inner.copy(privateKey = "")
            val token = generationToken + 1
            generationToken = token
            generating = true
            generationJob = scope.launch {
                try {
                    val outerAccount = WarpRegistrationProxy.register(
                        this@WarpInWarpActivity,
                        proxyGuid,
                    )
                    val innerAccount = WarpRegistrationProxy.register(
                        this@WarpInWarpActivity,
                        proxyGuid,
                    )
                    check(outerAccount.privateKey != innerAccount.privateKey) {
                        "WARP registration returned the same key for Inner and Outer"
                    }
                    if (generationToken == token) {
                        proxyChoices = WarpRegistrationProxy.choices(editGuid)
                        outer = outer.withAccount(outerAccount)
                        inner = inner.withAccount(innerAccount)
                        toastSuccess(R.string.toast_success)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (generationToken == token) {
                        proxyChoices = WarpRegistrationProxy.choices(editGuid)
                            toast(error.message ?: getString(R.string.toast_failure))
                    }
                } finally {
                    if (generationToken == token) generating = false
                }
            }
        }

        val importProfile = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) scope.launch {
                val imported = try {
                    val raw = withContext(Dispatchers.IO) { readProfileText(uri) }
                    WarpShareCodec.decode(raw) as? WarpShareCodec.Decoded.Plus
                        ?: error(getString(R.string.toast_failure))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    toast(error.message ?: getString(R.string.toast_failure))
                    return@launch
                }
                cancelEndpointWork()
                val parent = imported.parent
                remarks = parent.remarks.ifBlank { "WARP Plus" }
                inner = WarpLayerState.from(imported.inner, WarpLayerState.innerDefault())
                outer = WarpLayerState.from(imported.outer, WarpLayerState.outerDefault())
                autoScanEndpoint = parent.autoScanEndpoint
                    ?: (WarpPlusConfig.normalizeEndpointTestMode(parent.warpEndpointTestMode) !=
                        WarpPlusConfig.ENDPOINT_MODE_CUSTOM)
                outerCandidates = parent.awgEndpointCandidates.orEmpty()
                innerCandidates = outerCandidates
                registrationProxyGuid = WarpRegistrationProxy.normalizeSelection(parent.warpRegistrationProxyGuid)
                fastFinalMaskJson = parent.warpFastFinalMask.orEmpty()
                allFinalMaskJson = parent.warpAllFinalMask.orEmpty()
                customFinalMaskJson = imported.outer.finalMask.orEmpty()
                    .ifBlank { WarpPlusConfig.DEFAULT_FINAL_MASK }
                val enabled = parent.warpFinalMaskEnabled ?: true
                val selectedMask = if (autoScanEndpoint) {
                    fastFinalMaskJson.ifBlank { allFinalMaskJson.ifBlank { WarpPlusConfig.DEFAULT_FINAL_MASK } }
                } else {
                    customFinalMaskJson
                }
                finalMask = WarpFinalMaskState.fromJson(selectedMask).copy(enabled = enabled)
                psiphon = parent.psiphonEnabled
                tor = parent.torEnabled
                torBridgeMode = parent.torBridgeMode.orEmpty().ifBlank { "auto" }
                torBridgeSources = parent.torBridgeSources.orEmpty().ifBlank { "auto" }
                torBridgeLines = parent.torBridgeLines.orEmpty()
                region = parent.psiphonRegion.orEmpty().ifBlank { "ANY" }
                psiphonMode = parent.psiphonMode.orEmpty().ifBlank { "auto" }
                psiphonCdnIps = parent.psiphonCdnIps.orEmpty()
                psiphonCdnSni = parent.psiphonCdnSni.orEmpty()
                psiphonCdnSets = parent.psiphonCdnSets.orEmpty()
                toastSuccess(R.string.toast_success)
            }
        }

        LaunchedEffect(editGuid) {
            if (outer.privateKey.isBlank() || inner.privateKey.isBlank()) {
                startWarpGeneration(registrationProxyGuid)
            }
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    title = "WARP Plus",
                    onBackClick = { finish() },
                    actions = {
                        IconButton(onClick = { importProfile.launch(arrayOf("text/plain", "application/json")) }) {
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
                        IconButton(
                            enabled = !generating && outer.privateKey.isNotBlank() && inner.privateKey.isNotBlank(),
                            onClick = {
                                saveServer(
                                    remarks,
                                    outer,
                                    inner,
                                    finalMask.toJsonOrNull(),
                                    finalMask.enabled,
                                    psiphon,
                                    tor,
                                    torBridgeMode,
                                    torBridgeSources,
                                    torBridgeLines,
                                    region,
                                    psiphonMode,
                                    psiphonCdnIps,
                                    psiphonCdnSni,
                                    psiphonCdnSets,
                                    fastFinalMaskJson,
                                    allFinalMaskJson,
                                    registrationProxyGuid,
                                    outerCandidates,
                                    autoScanEndpoint,
                                )
                            },
                        ) {
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
                state = lazyListState,
                modifier = Modifier.fillMaxSize().padding(padding).verticalScrollbar(lazyListState),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                        scanInProgress = generating || scanJob?.isActive == true,
                        candidateGroups = listOf("WARP Plus" to EndpointScannerCli.decodeCandidates(innerCandidates)),
                        selectedEndpoints = mapOf(
                            "WARP Plus" to "${inner.server.trim().let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }}:${inner.port}",
                        ),
                        onScan = { scanOptions ->
                            cancelEndpointWork()
                            fun account(layer: WarpLayerState) = EndpointScannerCli.accountFromProfile(
                                ProfileItem.create(EConfigType.WIREGUARD).copy(
                                    server = layer.server,
                                    serverPort = layer.port,
                                    secretKey = layer.privateKey,
                                    publicKey = layer.peerPublicKey,
                                    localAddress = layer.localAddress,
                                    reserved = layer.reserved,
                                    mtu = layer.mtu.toIntOrNull() ?: 1280,
                                ),
                            )
                            val innerAccount = account(inner)
                            if (innerAccount == null) {
                                toast("Generate the inner WARP key before scanning")
                            } else {
                                scanJob = scope.launch {
                                    try {
                                        val foundInner = EndpointScannerCli.scanWireGuardEndpoints(
                                            this@WarpInWarpActivity,
                                            innerAccount,
                                            scanGuid = scanGuid,
                                            progressLabel = "Inner WARP",
                                            scanOptions = scanOptions,
                                        )
                                        innerCandidates = EndpointScannerCli.encodeCandidates(foundInner)
                                        outerCandidates = innerCandidates
                                        AwgEndpointScanState.complete(
                                            scanGuid,
                                            "Endpoint selected: ${foundInner.first().endpoint}",
                                            foundInner.first().endpoint,
                                        )
                                    } catch (cancelled: CancellationException) {
                                        AwgEndpointScanState.clear(scanGuid)
                                        throw cancelled
                                    } catch (_: Throwable) {
                                        AwgEndpointScanState.fail(scanGuid, "Endpoint scan failed")
                                    }
                                }
                            }
                        },
                        onCancel = {
                            cancelEndpointWork()
                        },
                        onGenerateKey = {
                            startWarpGeneration(registrationProxyGuid)
                        },
                        generateKeyLabel = "Generate WARP Key",
                        onSelect = { title, candidate ->
                            val (host, port) = AmneziaWgFmt.splitEndpoint(candidate.endpoint)
                            if (!host.isNullOrBlank() && !port.isNullOrBlank()) {
                                inner = inner.copy(server = host, port = port)
                                outer = outer.copy(server = host, port = port)
                                autoScanEndpoint = false
                            }
                        },
                    )
                }
                item { FormTextField(stringResource(R.string.server_lab_remarks), remarks, { remarks = it }) }
                item {
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
                            registrationProxyGuid = nextProxyGuid
                            startWarpGeneration(nextProxyGuid)
                        },
                    )
                }
                item {
                    WarpLayerFields(
                        title = "Outer WARP",
                        state = outer,
                        onStateChange = {
                            if (it.server != outer.server || it.port != outer.port) autoScanEndpoint = false
                            outer = it
                        },
                    )
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                item {
                    WarpLayerFields(
                        title = "Inner WARP",
                        state = inner,
                        onStateChange = {
                            if (it.server != inner.server || it.port != inner.port) autoScanEndpoint = false
                            inner = it
                        },
                    )
                }
                item {
                    WarpFinalMaskFields(
                        state = finalMask,
                        onStateChange = {
                            finalMask = it
                            val json = it.toJsonOrNull().orEmpty()
                            if (autoScanEndpoint) fastFinalMaskJson = json else customFinalMaskJson = json
                        },
                        onFind = { showFinalMaskFinder = true },
                        onReset = {
                            finalMask = WarpFinalMaskState
                                .fromJson(WarpPlusConfig.DEFAULT_FINAL_MASK)
                                .copy(enabled = true)
                        },
                    )
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
                        region = region,
                        onEnabledChange = { psiphon = it; if (it) tor = false },
                        onTorEnabledChange = { tor = it; if (it) psiphon = false },
                        enabledTitle = "Psiphon Over WARP In WARP",
                        torEnabledTitle = "Tor Over WARP In WARP",
                        onRegionChange = { region = it },
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
            }
        }

        if (showDelete) {
            DeleteConfirmDialog(
                message = stringResource(R.string.confirm_delete_profile),
                onConfirm = { showDelete = false; deleteProfile() },
                onDismiss = { showDelete = false },
            )
        }
        if (showFinalMaskFinder) {
            FinalMaskSearchDialog(
                guid = editGuid.ifBlank { "warp-in-warp-new" },
                candidateSource = { warpFinalMaskCandidates() },
                onSearch = { candidates, onResult, onProgress, onFinished ->
                    runWarpFinalMaskSearch(candidates, onResult, onProgress, onFinished)
                },
                onCancelSearch = { finalMaskSearchJob?.cancel() },
                onApply = {
                    finalMask = WarpFinalMaskState.fromJson(it).copy(enabled = true)
                    if (autoScanEndpoint) fastFinalMaskJson = it else customFinalMaskJson = it
                    showFinalMaskFinder = false
                },
                onDismiss = { showFinalMaskFinder = false },
            )
        }
    }

    private fun runWarpFinalMaskSearch(
        candidates: List<FinalMaskCandidate>,
        onResult: (FinalMaskCandidate, Long) -> Unit,
        onProgress: (Int) -> Unit,
        onFinished: () -> Unit,
    ) {
        val parentGuid = editGuid.takeIf { it.isNotBlank() }
        val outerGuid = initialOuterGuid
        if (parentGuid == null || outerGuid.isNullOrBlank()) {
            toast(getString(R.string.warp_final_mask_save_first))
            onFinished()
            return
        }

        finalMaskSearchJob = (application as AngApplication).applicationScope.launch(Dispatchers.IO) {
            val original = MmkvManager.decodeServerConfig(outerGuid)
            if (original == null) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.warp_outer_config_not_found))
                    onFinished()
                }
                return@launch
            }
            try {
                withContext(Dispatchers.Main) { LauncherManager.stopService(this@WarpInWarpActivity) }
                kotlinx.coroutines.delay(700L)
                candidates.forEachIndexed { index, candidate ->
                    try {
                        MmkvManager.encodeServerConfig(outerGuid, original.copy(finalMask = candidate.json))
                        withContext(Dispatchers.Main) {
                            if (index == 0) LauncherManager.startProxyOnlyService(this@WarpInWarpActivity, parentGuid)
                            else {
                                LauncherManager.stopService(this@WarpInWarpActivity)
                                kotlinx.coroutines.delay(350L)
                                LauncherManager.startProxyOnlyService(this@WarpInWarpActivity, parentGuid)
                            }
                        }
                        kotlinx.coroutines.delay(if (index == 0) 1200L else 900L)
                        val delay = liveWarpProbeDelay(timeoutMs = 2500)
                        if (delay >= 0L) withContext(Dispatchers.Main) { onResult(candidate, delay) }
                    } finally {
                        withContext(Dispatchers.Main) { onProgress(index + 1) }
                    }
                }
            } catch (_: CancellationException) {
                // Keep partial results visible when the search is cancelled.
            } finally {
                MmkvManager.encodeServerConfig(outerGuid, original)
                withContext(NonCancellable + Dispatchers.Main) {
                    LauncherManager.stopService(this@WarpInWarpActivity)
                    if (isRunning) LauncherManager.startService(this@WarpInWarpActivity, parentGuid)
                    onFinished()
                }
            }
        }.also { job -> job.invokeOnCompletion { if (finalMaskSearchJob === job) finalMaskSearchJob = null } }
    }

    private fun saveServer(
        remarks: String,
        outer: WarpLayerState,
        inner: WarpLayerState,
        finalMask: String?,
        finalMaskEnabled: Boolean,
        psiphon: Boolean,
        tor: Boolean,
        torBridgeMode: String,
        torBridgeSources: String,
        torBridgeLines: String,
        region: String,
        psiphonMode: String,
        psiphonCdnIps: String,
        psiphonCdnSni: String,
        psiphonCdnSets: String,
        fastFinalMask: String?,
        allFinalMask: String?,
        registrationProxyGuid: String,
        scannedOuterCandidates: String,
        autoScanEndpoint: Boolean,
    ) {
        val normalizedEndpointTestMode = if (autoScanEndpoint) {
            WarpPlusConfig.ENDPOINT_MODE_AUTO
        } else {
            WarpPlusConfig.ENDPOINT_MODE_CUSTOM
        }
        if (remarks.isBlank()) {
            toast(R.string.server_lab_remarks)
            return
        }
        if (outer.privateKey.isBlank() || inner.privateKey.isBlank()) {
            toast(R.string.warp_in_warp_generate_first)
            return
        }
        val activeFinalMask = finalMask?.takeIf { it.isNotBlank() }
        val effectiveFinalMask = when {
            !finalMaskEnabled -> null
            normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_CUSTOM ->
                activeFinalMask ?: WarpPlusConfig.DEFAULT_FINAL_MASK
            else -> activeFinalMask ?: WarpPlusConfig.DEFAULT_FINAL_MASK
        }
        val persistedFastFinalMask = when {
            !finalMaskEnabled -> null
            normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_AUTO ->
                activeFinalMask ?: WarpPlusConfig.DEFAULT_FINAL_MASK
            else -> fastFinalMask?.takeIf { it.isNotBlank() }
        }
        val persistedAllFinalMask = if (!finalMaskEnabled) null else allFinalMask?.takeIf { it.isNotBlank() }
        val parentGuid = editGuid.ifBlank { warpPlusDraftGuid ?: UUID.randomUUID().toString() }
        val componentSuffix = parentGuid.take(8)
        val outerRemark = "$remarks • Outer WARP • $componentSuffix"
        val innerRemark = "$remarks • Inner WARP • $componentSuffix"
        val outerGuid = initialOuterGuid ?: UUID.randomUUID().toString()
        val innerGuid = initialInnerGuid ?: UUID.randomUUID().toString()
        val previousSelection = MmkvManager.getSelectServer()
        val base = ProfileItem.create(EConfigType.WIREGUARD)
        fun layerProfile(state: WarpLayerState, layerRemark: String, isOuter: Boolean) = base.copy(
            remarks = layerRemark,
            subscriptionId = subscriptionId,
            managedBy = parentGuid,
            server = state.server.trim(),
            serverPort = state.port.toIntOrNull()?.takeIf { it in 1..65535 }?.toString() ?: "2408",
            secretKey = state.privateKey.trim(),
            publicKey = state.peerPublicKey.trim(),
            localAddress = state.localAddress.trim(),
            reserved = state.reserved.trim(),
            mtu = state.mtu.toIntOrNull() ?: 1280,
            warpKeepAlive = state.keepAlive.toIntOrNull()?.coerceIn(0, 120) ?: WarpPlusConfig.DEFAULT_KEEP_ALIVE,
            // Match Xray's WARP chain: FinalMask belongs to the outer
            // transport hop, while the inner hop dials it via dialerProxy.
            finalMask = effectiveFinalMask?.takeIf { isOuter && it.isNotBlank() },
        )
        val outerProfile = layerProfile(outer, outerRemark, true)
        val innerProfile = layerProfile(inner, innerRemark, false)
        com.v2ray.ang.core.EndpointScanSettings.transfer(warpPlusDraftGuid ?: editGuid, parentGuid)
        MmkvManager.encodeServerConfig(outerGuid, outerProfile)
        MmkvManager.encodeServerConfig(innerGuid, innerProfile)
        val parent = ProfileItem.create(EConfigType.PROXYCHAIN).apply {
            this.remarks = remarks.trim()
            this.subscriptionId = subscriptionId
            // CoreConfigManager links each hop to the next one with
            // dialerProxy, so the effective topology is inner -> outer.
            proxyChainProfiles = "$innerRemark,$outerRemark"
            description = WarpPlusConfig.DESCRIPTION
            warpRegistrationProxyGuid = registrationProxyGuid
            warpEndpointTestEnabled = true
            warpEndpointTestMode = normalizedEndpointTestMode
            this.autoScanEndpoint = autoScanEndpoint
            awgEndpointCandidates = scannedOuterCandidates.ifBlank { null }
            warpFastFinalMask = persistedFastFinalMask
            warpAllFinalMask = persistedAllFinalMask
            warpSkipEndpointTestOnce = false
            warpFinalMaskEnabled = finalMaskEnabled
            psiphonEnabled = psiphon
            torEnabled = tor
            this.torBridgeMode = torBridgeMode.ifBlank { "auto" }.lowercase()
            this.torBridgeSources = torBridgeSources.ifBlank { "auto" }.lowercase()
            this.torBridgeLines = torBridgeLines.trim().ifBlank { null }
            psiphonRegion = region.ifBlank { "ANY" }.uppercase()
            this.psiphonMode = psiphonMode.ifBlank { "auto" }.lowercase()
            this.psiphonCdnIps = psiphonCdnIps.trim().ifBlank { null }
            this.psiphonCdnSni = psiphonCdnSni.trim().ifBlank { null }
            this.psiphonCdnSets = psiphonCdnSets.trim().ifBlank { null }
            pingNgProfile = PingNgCompat.PROFILE_OFF
            pingNgDesyncArgs = null
        }
        MmkvManager.encodeServerConfig(parentGuid, parent)
        if (previousSelection.isNullOrBlank() || previousSelection == outerGuid || previousSelection == innerGuid) {
            MmkvManager.setSelectServer(parentGuid)
        }
        initialChildGuids.filter { it != outerGuid && it != innerGuid }.forEach(MmkvManager::removeServer)
        toastSuccess(R.string.toast_success)
        ProfileEditorResult.run { finishSaved(parentGuid, isRunning) }
    }

    private fun readProfileText(uri: Uri): String {
        val input = contentResolver.openInputStream(uri)
            ?: error("Could not open the selected profile")
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8_192)
            while (true) {
                val remaining = 2 * 1024 * 1024 + 1 - text.length
                if (remaining <= 0) error("Selected profile is too large")
                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                text.append(buffer, 0, count)
            }
            require(text.length <= 2 * 1024 * 1024) { "Selected profile is too large" }
            text.toString()
        }
    }

    private fun deleteProfile() {
        if (editGuid.isBlank() || isRunning || MmkvManager.getSelectServer() == editGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        initialChildGuids.forEach(MmkvManager::removeServer)
        MmkvManager.removeServer(editGuid)
        ProfileEditorResult.run { finishDeleted(editGuid) }
    }
}

@Composable
private fun WarpLayerFields(
    title: String,
    state: WarpLayerState,
    onStateChange: (WarpLayerState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        FormTextField("Server / Endpoint", state.server, { onStateChange(state.copy(server = it)) })
        FormTextField("Port", state.port, { onStateChange(state.copy(port = it)) }, keyboardType = KeyboardType.Number)
        FormTextField("Peer public key", state.peerPublicKey, { onStateChange(state.copy(peerPublicKey = it)) })
        FormTextField("Private key", state.privateKey, { onStateChange(state.copy(privateKey = it)) })
        FormTextField("Local address", state.localAddress, { onStateChange(state.copy(localAddress = it)) })
        FormTextField("Reserved", state.reserved, { onStateChange(state.copy(reserved = it)) })
        FormTextField("MTU", state.mtu, { onStateChange(state.copy(mtu = it)) }, keyboardType = KeyboardType.Number)
        FormTextField("Keep alive", state.keepAlive, { onStateChange(state.copy(keepAlive = it)) }, keyboardType = KeyboardType.Number)
    }
}

@Composable
internal fun WarpFinalMaskFields(
    state: WarpFinalMaskState,
    onStateChange: (WarpFinalMaskState) -> Unit,
    onFind: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsSwitchItem(
            title = "UDP FinalMask noise",
            summary = "Applied internally to the outer WARP hop; no JSON is shown.",
            checked = state.enabled,
            onCheckedChange = { onStateChange(state.copy(enabled = it)) },
        )
        if (state.enabled) {
            Text("FinalMask settings", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth()) {
                FormTextField("Reset min", state.resetMin, { onStateChange(state.copy(resetMin = it)) }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                FormTextField("Reset max", state.resetMax, { onStateChange(state.copy(resetMax = it)) }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            }
            FormTextField("Noise entries", state.noiseCount, { onStateChange(state.copy(noiseCount = it)) }, keyboardType = KeyboardType.Number)
            Row(Modifier.fillMaxWidth()) {
                FormTextField("Rand min", state.randMin, { onStateChange(state.copy(randMin = it)) }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                FormTextField("Rand max", state.randMax, { onStateChange(state.copy(randMax = it)) }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                FormTextField("Range min", state.rangeMin, { onStateChange(state.copy(rangeMin = it)) }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                FormTextField("Range max", state.rangeMax, { onStateChange(state.copy(rangeMax = it)) }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                FormTextField("Delay min", state.delayMin, { onStateChange(state.copy(delayMin = it)) }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                FormTextField("Delay max", state.delayMax, { onStateChange(state.copy(delayMax = it)) }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onFind,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) {
                    Text("Find FinalMask")
                }
                Button(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                ) {
                    Text("Reset FinalMask")
                }
            }
        }
    }
    }

    private fun liveWarpProbeDelay(timeoutMs: Int): Long {
        val urls = listOf(
            "http://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204",
            SettingsManager.getDelayTestUrl(),
        ).map(String::trim).filter(String::isNotBlank).distinct()
        for (url in urls) {
            val delay = SpeedtestManager.liveTunnelDelay(url, timeoutMs)
            if (delay >= 0L) return delay
        }
        return -1L
    }

@Composable
private fun DesyncPickerDialog(onApply: (String) -> Unit, onDismiss: () -> Unit) {
    val options = remember {
        listOf(PingNgCompat.PROFILE_LIGHT, PingNgCompat.PROFILE_BALANCED, PingNgCompat.PROFILE_SEVERE, PingNgCompat.PROFILE_ADAPTIVE)
            .map { it to PingNgCompat.getPresetArguments(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find Desync Setting") },
        text = {
            LazyColumn { options.forEach { (name, args) ->
                item {
                    TextButton(onClick = { onApply(args) }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                }
            } }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

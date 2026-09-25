package com.v2ray.ang.ui.server

import android.os.Bundle
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.WarpAccount
import com.v2ray.ang.core.WarpRegistrationProxy
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
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
import kotlin.random.Random

private data class WarpEndpoint(val host: String, val port: Int)

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
    val endpointCandidates: String = "",
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
            endpointCandidates = WarpPlusConfig.DEFAULT_OUTER_ENDPOINTS,
        )

        fun innerDefault() = WarpLayerState(
            server = "162.159.192.1",
            port = "2408",
            endpointCandidates = WarpPlusConfig.DEFAULT_INNER_ENDPOINTS,
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
                endpointCandidates = it.warpEndpointCandidates.orEmpty().ifBlank { fallback.endpointCandidates },
            )
        } ?: fallback
    }
}

class WarpInWarpActivity : BaseComponentActivity() {
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
    private var initialRegion = "ANY"
    private var initialPsiphonMode = "auto"
    private var initialPsiphonCdnIps = ""
    private var initialPsiphonCdnSni = ""
    private var initialPsiphonCdnSets = ""
    private var initialEndpointTestMode = "Fast"
    private var initialRegistrationProxyGuid = WarpRegistrationProxy.AUTO
    private var initialChildGuids = emptyList<String>()
    private var initialOuterGuid: String? = null
    private var initialInnerGuid: String? = null
    private var finalMaskSearchJob: Job? = null
    private var endpointSearchJob: Job? = null

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
        initialRegion = parent?.psiphonRegion.orEmpty().ifBlank { "ANY" }
        initialPsiphonMode = parent?.psiphonMode.orEmpty().ifBlank { "auto" }
        initialPsiphonCdnIps = parent?.psiphonCdnIps.orEmpty()
        initialPsiphonCdnSni = parent?.psiphonCdnSni.orEmpty()
        initialPsiphonCdnSets = parent?.psiphonCdnSets.orEmpty()
        initialEndpointTestMode = WarpPlusConfig.normalizeEndpointTestMode(parent?.warpEndpointTestMode)
        initialRegistrationProxyGuid = parent?.warpRegistrationProxyGuid ?: WarpRegistrationProxy.AUTO
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
        initialFinalMask = when (initialEndpointTestMode) {
            WarpPlusConfig.ENDPOINT_MODE_FAST -> initialFastFinalMask.ifBlank { WarpPlusConfig.DEFAULT_FINAL_MASK }
            WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
            WarpPlusConfig.ENDPOINT_MODE_SLOW -> initialAllFinalMask.ifBlank { WarpPlusConfig.DEFAULT_FINAL_MASK }
            else -> initialCustomFinalMask
        }
        initialFinalMaskEnabled = parent?.warpFinalMaskEnabled ?: true
        initialOuterGuid = children.getOrNull(outerIndex)?.first
        initialInnerGuid = children.getOrNull(innerIndex)?.first
        val outerFallback = WarpLayerState.outerDefault().copy(
            endpointCandidates = parent?.warpOuterEndpointCandidates.orEmpty()
                .ifBlank { WarpPlusConfig.DEFAULT_OUTER_ENDPOINTS },
        )
        val innerFallback = WarpLayerState.innerDefault().copy(
            endpointCandidates = parent?.warpInnerEndpointCandidates.orEmpty()
                .ifBlank { WarpPlusConfig.DEFAULT_INNER_ENDPOINTS },
        )
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
        var finalMaskPickedFromFinder by remember { mutableStateOf(false) }
        var psiphon by remember { mutableStateOf(initialPsiphon) }
        var region by remember { mutableStateOf(initialRegion) }
        var psiphonMode by remember { mutableStateOf(initialPsiphonMode) }
        var psiphonCdnIps by remember { mutableStateOf(initialPsiphonCdnIps) }
        var psiphonCdnSni by remember { mutableStateOf(initialPsiphonCdnSni) }
        var psiphonCdnSets by remember { mutableStateOf(initialPsiphonCdnSets) }
        var endpointTestMode by remember { mutableStateOf(initialEndpointTestMode) }
        var registrationProxyGuid by remember { mutableStateOf(initialRegistrationProxyGuid) }
        var proxyChoices by remember(editGuid) { mutableStateOf(WarpRegistrationProxy.choices(editGuid)) }
        val proxyLabel = if (registrationProxyGuid == WarpRegistrationProxy.AUTO) {
            WarpRegistrationProxy.AUTO_LABEL
        } else {
            proxyChoices.firstOrNull { it.guid == registrationProxyGuid }?.label
                ?: WarpRegistrationProxy.AUTO_LABEL
        }
        var generating by remember { mutableStateOf(false) }
        var showFinalMaskFinder by remember { mutableStateOf(false) }
        val lazyListState = rememberLazyListState()

        LaunchedEffect(Unit) {
            if (editGuid.isBlank() && outer.privateKey.isBlank() && inner.privateKey.isBlank()) {
                generating = true
                runCatching {
                    val outerAccount = WarpRegistrationProxy.register(
                        this@WarpInWarpActivity,
                        registrationProxyGuid,
                        editGuid,
                    )
                    val innerAccount = WarpRegistrationProxy.register(
                        this@WarpInWarpActivity,
                        registrationProxyGuid,
                        editGuid,
                    )
                    outer = outer.withAccount(outerAccount)
                    inner = inner.withAccount(innerAccount)
                }.onFailure { toast(it.message ?: "WARP registration failed") }
                proxyChoices = WarpRegistrationProxy.choices(editGuid)
                generating = false
            }
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    title = "WARP Plus",
                    onBackClick = { finish() },
                    actions = {
                        TextButton(onClick = {
                            saveServer(
                                remarks,
                                outer,
                                inner,
                                finalMask.toJsonOrNull(),
                                finalMask.enabled,
                                psiphon,
                                region,
                                psiphonMode,
                                psiphonCdnIps,
                                psiphonCdnSni,
                                psiphonCdnSets,
                                endpointTestMode,
                                finalMaskPickedFromFinder,
                                fastFinalMaskJson,
                                allFinalMaskJson,
                                registrationProxyGuid,
                            )
                        }) { Text(stringResource(R.string.action_save)) }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize().padding(padding).verticalScrollbar(lazyListState),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item { FormTextField(stringResource(R.string.server_lab_remarks), remarks, { remarks = it }) }
                item {
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
                    )
                }
                item {
                    WarpLayerFields(
                        title = "Outer WARP",
                        state = outer,
                        generating = generating,
                        onStateChange = { outer = it },
                        onGenerate = {
                            scope.launch {
                                generating = true
                                runCatching {
                                    WarpRegistrationProxy.register(
                                        this@WarpInWarpActivity,
                                        registrationProxyGuid,
                                        editGuid,
                                    )
                                }
                                    .onSuccess {
                                        proxyChoices = WarpRegistrationProxy.choices(editGuid)
                                        outer = outer.withAccount(it)
                                        toastSuccess(R.string.toast_success)
                                    }
                                    .onFailure {
                                        proxyChoices = WarpRegistrationProxy.choices(editGuid)
                                        toast(it.message ?: "WARP registration failed")
                                    }
                                generating = false
                            }
                        },
                    )
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                item {
                    WarpLayerFields(
                        title = "Inner WARP",
                        state = inner,
                        generating = generating,
                        onStateChange = { inner = it },
                        onGenerate = {
                            scope.launch {
                                generating = true
                                runCatching {
                                    WarpRegistrationProxy.register(
                                        this@WarpInWarpActivity,
                                        registrationProxyGuid,
                                        editGuid,
                                    )
                                }
                                    .onSuccess {
                                        proxyChoices = WarpRegistrationProxy.choices(editGuid)
                                        inner = inner.withAccount(it)
                                        toastSuccess(R.string.toast_success)
                                    }
                                    .onFailure {
                                        proxyChoices = WarpRegistrationProxy.choices(editGuid)
                                        toast(it.message ?: "WARP registration failed")
                                    }
                                generating = false
                            }
                        },
                    )
                }
                item {
                    WarpFinalMaskFields(
                        state = finalMask,
                        onStateChange = {
                            finalMask = it
                            val json = it.toJsonOrNull().orEmpty()
                            when (WarpPlusConfig.normalizeEndpointTestMode(endpointTestMode)) {
                                WarpPlusConfig.ENDPOINT_MODE_FAST -> fastFinalMaskJson = json
                                WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
                                WarpPlusConfig.ENDPOINT_MODE_SLOW -> allFinalMaskJson = json
                                WarpPlusConfig.ENDPOINT_MODE_CUSTOM -> customFinalMaskJson = json
                            }
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
                    FormDropdownField(
                        label = "Endpoint test mode",
                        value = endpointTestMode,
                        options = listOf(
                            WarpPlusConfig.ENDPOINT_MODE_FAST,
                            WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
                            WarpPlusConfig.ENDPOINT_MODE_SLOW,
                            WarpPlusConfig.ENDPOINT_MODE_CUSTOM,
                        ),
                        onValueChange = {
                            val currentMode = WarpPlusConfig.normalizeEndpointTestMode(endpointTestMode)
                            val currentJson = finalMask.toJsonOrNull().orEmpty()
                            when (currentMode) {
                                WarpPlusConfig.ENDPOINT_MODE_FAST -> fastFinalMaskJson = currentJson
                                WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
                                WarpPlusConfig.ENDPOINT_MODE_SLOW -> allFinalMaskJson = currentJson
                                WarpPlusConfig.ENDPOINT_MODE_CUSTOM -> customFinalMaskJson = currentJson
                            }
                            endpointTestMode = it
                            val nextMaskJson = when (WarpPlusConfig.normalizeEndpointTestMode(it)) {
                                WarpPlusConfig.ENDPOINT_MODE_FAST -> fastFinalMaskJson
                                WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
                                WarpPlusConfig.ENDPOINT_MODE_SLOW -> allFinalMaskJson
                                WarpPlusConfig.ENDPOINT_MODE_CUSTOM -> customFinalMaskJson
                                else -> ""
                            }.ifBlank { WarpPlusConfig.DEFAULT_FINAL_MASK }
                            finalMask = WarpFinalMaskState.fromJson(nextMaskJson)
                                .copy(enabled = finalMask.enabled)
                        },
                        supportingText = when (endpointTestMode) {
                            WarpPlusConfig.ENDPOINT_MODE_FAST -> "First responding endpoint is selected automatically when Start is pressed."
                            WarpPlusConfig.ENDPOINT_MODE_MEDIUM -> "Scans Outer only, then uses its best endpoint for inner and outer."
                            WarpPlusConfig.ENDPOINT_MODE_SLOW -> "Searches separate healthy endpoints for inner and outer."
                            else -> "Tests the endpoint lists entered in the two WARP sections."
                        },
                    )
                }
                item {
                    PsiphonEditorFields(
                        enabled = psiphon,
                        region = region,
                        onEnabledChange = { psiphon = it },
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

        if (showFinalMaskFinder) {
            FinalMaskSearchDialog(
                guid = editGuid.ifBlank { "warp-in-warp-new" },
                candidateSource = { warpFinalMaskCandidates() },
                onSearch = { candidates, onResult, onProgress, onFinished ->
                    runWarpFinalMaskSearch(candidates, onResult, onProgress, onFinished)
                },
                onCancelSearch = { finalMaskSearchJob?.cancel() },
                onApply = {
                    val appliedMode = WarpPlusConfig.normalizeEndpointTestMode(endpointTestMode)
                    finalMask = WarpFinalMaskState.fromJson(it).copy(enabled = true)
                    when (appliedMode) {
                        WarpPlusConfig.ENDPOINT_MODE_FAST -> fastFinalMaskJson = it
                        WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
                        WarpPlusConfig.ENDPOINT_MODE_SLOW -> allFinalMaskJson = it
                        WarpPlusConfig.ENDPOINT_MODE_CUSTOM -> customFinalMaskJson = it
                    }
                    finalMaskPickedFromFinder = true
                    // Pin the pair that was used while finding this mask in
                    // Custom mode. The next Start must not retest it and
                    // replace it with a default/random endpoint.
                    if (appliedMode == WarpPlusConfig.ENDPOINT_MODE_CUSTOM) {
                        val innerEndpoint = formatWarpEndpoint(inner.server, inner.port)
                        val outerEndpoint = formatWarpEndpoint(outer.server, outer.port)
                        if (innerEndpoint != null && outerEndpoint != null) {
                            inner = inner.copy(endpointCandidates = innerEndpoint)
                            outer = outer.copy(endpointCandidates = outerEndpoint)
                        }
                    }
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
            toast("برای جست‌وجوی واقعی، ابتدا WARP in WARP را ذخیره کنید")
            onFinished()
            return
        }

        finalMaskSearchJob = (application as AngApplication).applicationScope.launch(Dispatchers.IO) {
            val original = MmkvManager.decodeServerConfig(outerGuid)
            if (original == null) {
                withContext(Dispatchers.Main) {
                    toast("کانفیگ Outer WARP پیدا نشد")
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

    private fun runWarpEndpointSearch(
        outer: WarpLayerState,
        inner: WarpLayerState,
        finalMask: String?,
        endpointTestMode: String,
        onFound: (Pair<WarpEndpoint, WarpEndpoint>, Long) -> Unit,
        onProgress: (Int, Int) -> Unit,
        onFinished: () -> Unit,
    ) {
        val parentGuid = editGuid.takeIf { it.isNotBlank() }
        val outerGuid = initialOuterGuid
        val innerGuid = initialInnerGuid
        if (parentGuid == null || outerGuid.isNullOrBlank() || innerGuid.isNullOrBlank()) {
            toast("ابتدا WARP Plus را ذخیره کنید، سپس endpointها را تست کنید")
            onFinished()
            return
        }

        val mode = WarpPlusConfig.normalizeEndpointTestMode(endpointTestMode)
        val isBuiltInMode = mode == WarpPlusConfig.ENDPOINT_MODE_FAST ||
            mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM ||
            mode == WarpPlusConfig.ENDPOINT_MODE_SLOW
        val builtInEndpoints = when (mode) {
            WarpPlusConfig.ENDPOINT_MODE_FAST -> WarpPlusConfig.FAST_ENDPOINTS
            WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
            WarpPlusConfig.ENDPOINT_MODE_SLOW -> WarpPlusConfig.ALL_ENDPOINTS
            else -> ""
        }
        // Parse the built-in pool once. In Medium/Slow mode it contains
        // thousands of addresses; making two copies and then materializing
        // every candidate pair caused the GC storm seen in the runtime log.
        val builtInPool = if (isBuiltInMode) {
            parseWarpEndpoints(builtInEndpoints, inner.server, inner.port)
        } else {
            null
        }
        val innerCandidates = builtInPool ?: parseWarpEndpoints(inner.endpointCandidates, inner.server, inner.port)
        val outerCandidates = builtInPool ?: parseWarpEndpoints(outer.endpointCandidates, outer.server, outer.port)
        val fastFirstEndpoint = WarpEndpoint("188.114.96.206", 878)
        val pairs = if (mode == WarpPlusConfig.ENDPOINT_MODE_FAST) {
            // Probe the requested endpoint on both WARP hops before trying
            // any other pair. A failed four-second probe then falls through
            // to the normal Fast candidate queue.
            listOf(fastFirstEndpoint to fastFirstEndpoint) +
                (outerCandidates.map { fastFirstEndpoint to it } + innerCandidates.map { it to fastFirstEndpoint })
                    .distinct()
                    .shuffled()
        } else if (mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM) {
            innerCandidates.distinct().map { endpoint -> endpoint to endpoint }
        } else if (mode == WarpPlusConfig.ENDPOINT_MODE_SLOW) {
            buildRandomEndpointPairs(innerCandidates, outerCandidates, 256)
                .filter { (innerEndpoint, outerEndpoint) -> innerEndpoint != outerEndpoint }
        } else {
            innerCandidates.flatMap { innerEndpoint ->
                outerCandidates.map { outerEndpoint -> innerEndpoint to outerEndpoint }
            }.distinct().take(64)
        }.distinct().let { ordered ->
            if (mode == WarpPlusConfig.ENDPOINT_MODE_FAST) ordered.take(256)
            else ordered.shuffled().take(256)
        }
        if (pairs.isEmpty()) {
            toast("هیچ endpoint معتبری برای تست وارد نشده است")
            onFinished()
            return
        }

        endpointSearchJob = (application as AngApplication).applicationScope.launch(Dispatchers.IO) {
            val originalOuter = MmkvManager.decodeServerConfig(outerGuid)
            val originalInner = MmkvManager.decodeServerConfig(innerGuid)
            if (originalOuter == null || originalInner == null) {
                withContext(Dispatchers.Main) {
                    toast("پروفایل‌های WARP Plus پیدا نشدند")
                    onFinished()
                }
                return@launch
            }

            val currentOuter = originalOuter.copy(
                server = outer.server.trim(),
                serverPort = outer.port.toIntOrNull()?.takeIf { it in 1..65535 }?.toString() ?: "2408",
                // Every endpoint check starts with the complete default-mask
                // WARP Plus configuration.
                finalMask = WarpPlusConfig.DEFAULT_FINAL_MASK,
                warpKeepAlive = outer.keepAlive.toIntOrNull()?.coerceIn(0, 120) ?: WarpPlusConfig.DEFAULT_KEEP_ALIVE,
                warpEndpointCandidates = outer.endpointCandidates.trim(),
            )
            val currentInner = originalInner.copy(
                server = inner.server.trim(),
                serverPort = inner.port.toIntOrNull()?.takeIf { it in 1..65535 }?.toString() ?: "2408",
                finalMask = null,
                warpKeepAlive = inner.keepAlive.toIntOrNull()?.coerceIn(0, 120) ?: WarpPlusConfig.DEFAULT_KEEP_ALIVE,
                warpEndpointCandidates = inner.endpointCandidates.trim(),
            )
            var best: Pair<WarpEndpoint, WarpEndpoint>? = null
            var bestDelay = Long.MAX_VALUE
            var wasCancelled = false
            try {
                withContext(Dispatchers.Main) { LauncherManager.stopService(this@WarpInWarpActivity) }
                kotlinx.coroutines.delay(700L)
                for (index in pairs.indices) {
                    val pair = pairs[index]
                    val (innerEndpoint, outerEndpoint) = pair
                    try {
                        MmkvManager.encodeServerConfig(
                            outerGuid,
                            currentOuter.copy(server = outerEndpoint.host, serverPort = outerEndpoint.port.toString()),
                        )
                        MmkvManager.encodeServerConfig(
                            innerGuid,
                            currentInner.copy(server = innerEndpoint.host, serverPort = innerEndpoint.port.toString()),
                        )
                        withContext(Dispatchers.Main) {
                            if (index > 0) {
                                LauncherManager.stopService(this@WarpInWarpActivity)
                                kotlinx.coroutines.delay(350L)
                            }
                            LauncherManager.startProxyOnlyService(this@WarpInWarpActivity, parentGuid)
                        }
                        kotlinx.coroutines.delay(if (index == 0) 1300L else 900L)
                        val delay = SpeedtestManager.liveTunnelDelay(
                            SettingsManager.getDelayTestUrl(),
                    // Give the explicitly requested endpoint pair a full
                    // four seconds before falling through to Fast search.
                            timeoutMs = if (mode == WarpPlusConfig.ENDPOINT_MODE_FAST && index == 0) 4000 else 2500,
                        )
                        if (delay >= 0L && delay < bestDelay) {
                            best = pair
                            bestDelay = delay
                            withContext(Dispatchers.Main) { onFound(pair, delay) }
                            // Fast and Medium are first-success modes. Slow
                            // continues through its distinct-pair queue so it
                            // can retain the lowest delay.
                            if (mode == WarpPlusConfig.ENDPOINT_MODE_FAST ||
                                mode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM
                            ) break
                        }
                    } finally {
                        withContext(Dispatchers.Main) { onProgress(index + 1, pairs.size) }
                    }
                }
            } catch (_: CancellationException) {
                // Keep the best successful endpoint found before cancellation.
                wasCancelled = true
            } finally {
                if (best == null) {
                    MmkvManager.encodeServerConfig(outerGuid, originalOuter)
                    MmkvManager.encodeServerConfig(innerGuid, originalInner)
                } else {
                    val bestPair = best!!
                    val (bestInner, bestOuter) = bestPair
                    MmkvManager.encodeServerConfig(
                        outerGuid,
                        currentOuter.copy(server = bestOuter.host, serverPort = bestOuter.port.toString()),
                    )
                    MmkvManager.encodeServerConfig(
                        innerGuid,
                        currentInner.copy(server = bestInner.host, serverPort = bestInner.port.toString()),
                    )
                    MmkvManager.decodeServerConfig(parentGuid)?.let { parent ->
                        MmkvManager.encodeServerConfig(
                            parentGuid,
                            parent.copy(
                                warpSelectedEndpoint = "inner ${bestInner.host}:${bestInner.port} • outer ${bestOuter.host}:${bestOuter.port}",
                                warpSkipEndpointTestOnce = false,
                            ),
                        )
                    }
                    withContext(NonCancellable + Dispatchers.Main) { onFound(bestPair, bestDelay) }
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    LauncherManager.stopService(this@WarpInWarpActivity)
                    // Built-in modes are actions, not dry-runs: after a
                    // healthy pair is saved, immediately start the profile.
                    if (!wasCancelled && best != null && (isRunning || mode != WarpPlusConfig.ENDPOINT_MODE_CUSTOM)
                    ) {
                        kotlinx.coroutines.delay(350L)
                        LauncherManager.startService(this@WarpInWarpActivity, parentGuid)
                    }
                    onFinished()
                }
            }
        }.also { job -> job.invokeOnCompletion { if (endpointSearchJob === job) endpointSearchJob = null } }
    }

    private fun saveServer(
        remarks: String,
        outer: WarpLayerState,
        inner: WarpLayerState,
        finalMask: String?,
        finalMaskEnabled: Boolean,
        psiphon: Boolean,
        region: String,
        psiphonMode: String,
        psiphonCdnIps: String,
        psiphonCdnSni: String,
        psiphonCdnSets: String,
        endpointTestMode: String,
        preserveEndpointSelection: Boolean,
        fastFinalMask: String?,
        allFinalMask: String?,
        registrationProxyGuid: String,
    ) {
        val normalizedEndpointTestMode = WarpPlusConfig.normalizeEndpointTestMode(endpointTestMode)
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
            normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_FAST ->
                activeFinalMask ?: WarpPlusConfig.DEFAULT_FINAL_MASK
            else -> fastFinalMask?.takeIf { it.isNotBlank() }
        }
        val persistedAllFinalMask = when {
            !finalMaskEnabled -> null
            normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_MEDIUM ||
                normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_SLOW ->
                activeFinalMask ?: WarpPlusConfig.DEFAULT_FINAL_MASK
            else -> allFinalMask?.takeIf { it.isNotBlank() }
        }
        fun endpointCandidatesFor(state: WarpLayerState): String =
            state.endpointCandidates.trim().ifBlank {
                formatWarpEndpoint(state.server, state.port)
            }
        val parentGuid = editGuid.ifBlank { UUID.randomUUID().toString() }
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
            warpEndpointCandidates = if (normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_CUSTOM) {
                endpointCandidatesFor(state)
            } else {
                state.endpointCandidates.trim()
            },
            // Match Xray's WARP chain: FinalMask belongs to the outer
            // transport hop, while the inner hop dials it via dialerProxy.
            finalMask = effectiveFinalMask?.takeIf { isOuter && it.isNotBlank() },
        )
        val outerProfile = layerProfile(outer, outerRemark, true)
        val innerProfile = layerProfile(inner, innerRemark, false)
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
            warpFastFinalMask = persistedFastFinalMask
            warpAllFinalMask = persistedAllFinalMask
            // Finding a mask in Fast/Medium/Slow must not bypass the next endpoint
            // search. Only an explicitly pinned Custom pair may skip it.
            warpSkipEndpointTestOnce = preserveEndpointSelection &&
                normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_CUSTOM
            warpFinalMaskEnabled = finalMaskEnabled
            warpInnerEndpointCandidates = if (normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_CUSTOM) {
                endpointCandidatesFor(inner)
            } else {
                inner.endpointCandidates.trim()
            }
            warpOuterEndpointCandidates = if (normalizedEndpointTestMode == WarpPlusConfig.ENDPOINT_MODE_CUSTOM) {
                endpointCandidatesFor(outer)
            } else {
                outer.endpointCandidates.trim()
            }
            psiphonEnabled = psiphon
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
}

@Composable
private fun WarpLayerFields(
    title: String,
    state: WarpLayerState,
    generating: Boolean,
    onStateChange: (WarpLayerState) -> Unit,
    onGenerate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = onGenerate,
            enabled = !generating,
            modifier = Modifier.padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(if (generating) "Generating…" else "Generate New WARP")
        }
        FormTextField("Server / Endpoint", state.server, { onStateChange(state.copy(server = it)) })
        FormTextField("Port", state.port, { onStateChange(state.copy(port = it)) }, keyboardType = KeyboardType.Number)
        FormTextField(
            "Test endpoints (host:port, comma separated)",
            state.endpointCandidates,
            { onStateChange(state.copy(endpointCandidates = it)) },
        )
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

    /**
 * Produces a bounded random queue without first building all inner×outer
 * combinations. Each selected endpoint gets a chance on both WARP hops.
 */
private fun buildRandomEndpointPairs(
    innerCandidates: List<WarpEndpoint>,
    outerCandidates: List<WarpEndpoint>,
    limit: Int,
): List<Pair<WarpEndpoint, WarpEndpoint>> {
    if (innerCandidates.isEmpty() || outerCandidates.isEmpty()) return emptyList()
    val innerOrder = innerCandidates.indices.shuffled(Random)
    val outerOrder = outerCandidates.indices.shuffled(Random)
    val anchorInner = innerCandidates[innerOrder.first()]
    val anchorOuter = outerCandidates[outerOrder.first()]
    val result = LinkedHashSet<Pair<WarpEndpoint, WarpEndpoint>>(limit)
    var index = 0
    while (result.size < limit && index < maxOf(innerOrder.size, outerOrder.size)) {
        val inner = innerCandidates[innerOrder[index % innerOrder.size]]
        val outer = outerCandidates[outerOrder[index % outerOrder.size]]
        result.add(inner to anchorOuter)
        if (result.size < limit) result.add(anchorInner to outer)
        index++
    }
    return result.toList().shuffled(Random)
}

private fun formatWarpEndpoint(hostValue: String, portValue: String): String {
    val host = hostValue.trim()
    val port = portValue.trim()
    if (host.isBlank()) return ""
    val displayHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return "$displayHost:${port.ifBlank { "2408" }}"
}

private fun parseWarpEndpoints(raw: String, fallbackHost: String, fallbackPort: String): List<WarpEndpoint> {
    val defaultPort = fallbackPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 2408
    return raw.split(',', ';', '\n', '\r')
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapNotNull { value ->
            if (value.startsWith("[")) {
                val close = value.indexOf(']')
                if (close <= 0) return@mapNotNull null
                val host = value.substring(1, close)
                val port = value.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                if (host.isNotBlank() && port in 1..65535) WarpEndpoint(host, port) else null
            } else {
                val separator = value.lastIndexOf(':')
                val hasPort = separator > 0 && value.indexOf(':') == separator
                val host = if (hasPort) value.substring(0, separator) else value
                val port = if (hasPort) value.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null else defaultPort
                if (host.isNotBlank() && port in 1..65535) WarpEndpoint(host, port) else null
            }
        }
        .ifEmpty { listOf(WarpEndpoint(fallbackHost.trim(), defaultPort)) }
        .distinct()
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

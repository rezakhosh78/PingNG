package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.WarpRegistrationProxy
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

/** Editor for a real WARP MASQUE/H2 profile backed by the standalone core. */
class WarpMasqueActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.WARP
    private var warpSearchDraftGuid: String? = null

    @Composable
    override fun ScreenContent() {
        var remarks by rememberSaveable { mutableStateOf(initialConfig.remarks.ifBlank { "WARP MASQUE/H2" }) }
        var registrationProxyGuid by rememberSaveable {
            mutableStateOf(initialConfig.warpRegistrationProxyGuid ?: WarpRegistrationProxy.AUTO)
        }
        val proxyChoices = remember(editGuid) { WarpRegistrationProxy.choices(editGuid) }
        val selectedProxyLabel = if (registrationProxyGuid == WarpRegistrationProxy.AUTO) {
            WarpRegistrationProxy.AUTO_LABEL
        } else {
            proxyChoices.firstOrNull { it.guid == registrationProxyGuid }?.label
                ?: WarpRegistrationProxy.AUTO_LABEL
        }
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
        var primaryEndpoint by rememberSaveable {
            mutableStateOf(initialConfig.warpMasquePrimaryEndpoint ?: WarpMasqueConfig.DEFAULT_ENDPOINT)
        }
        var endpointPort by rememberSaveable {
            mutableStateOf((initialConfig.warpMasqueEndpointPort ?: WarpMasqueConfig.DEFAULT_PORT).toString())
        }
        var endpointMode by rememberSaveable {
            mutableStateOf(initialConfig.warpMasqueEndpointMode ?: WarpMasqueConfig.ENDPOINT_MODE_AUTO)
        }
        var deviceName by rememberSaveable {
            mutableStateOf(initialConfig.warpMasqueDeviceName ?: WarpMasqueConfig.randomDeviceName())
        }
        var sni by rememberSaveable {
            mutableStateOf(initialConfig.warpMasqueSni
                ?.takeUnless { it == WarpMasqueConfig.LEGACY_DEFAULT_SNI } ?: WarpMasqueConfig.DEFAULT_SNI)
        }
        var dns by rememberSaveable {
            mutableStateOf(initialConfig.warpMasqueDns ?: WarpMasqueConfig.DEFAULT_DNS)
        }
        var http2Enabled by rememberSaveable {
            mutableStateOf(initialConfig.warpMasqueHttp2Enabled ?: true)
        }
        var psiphon by rememberSaveable { mutableStateOf(initialConfig.psiphonEnabled) }
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
        val listState = rememberLazyListState()

        Scaffold(
            topBar = {
                AppTopBar(
                    title = "WARP",
                    onBackClick = { finish() },
                    actions = {
                        TextButton(onClick = {
                            saveServer(
                                remarks = remarks,
                                registrationProxyGuid = registrationProxyGuid,
                                endpoints = endpoints,
                                primaryEndpoint = primaryEndpoint,
                                endpointPort = endpointPort,
                                deviceName = deviceName,
                                sni = sni,
                                dns = dns,
                                http2Enabled = http2Enabled,
                                psiphon = psiphon,
                                psiphonRegion = psiphonRegion,
                                psiphonMode = psiphonMode,
                                psiphonCdnIps = psiphonCdnIps,
                                psiphonCdnSni = psiphonCdnSni,
                                psiphonCdnSets = psiphonCdnSets,
                                desyncProfile = desyncProfile,
                                desyncArgs = desyncArgs,
                                endpointMode = endpointMode,
                            )
                        }) {
                            Text(stringResource(R.string.action_save))
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
                item { FormTextField(stringResource(R.string.server_lab_remarks), remarks, { remarks = it }) }
                item {
                    FormDropdownField(
                        label = "Proxy",
                        value = selectedProxyLabel,
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
                        onValueChange = { primaryEndpoint = it; endpointMode = WarpMasqueConfig.ENDPOINT_MODE_CUSTOM },
                        placeholder = WarpMasqueConfig.DEFAULT_ENDPOINT,
                        maxLines = 1,
                        keyboardType = KeyboardType.Uri,
                    )
                }
                item {
                    FormTextField(
                        label = "Endpoint port",
                        value = endpointPort,
                        onValueChange = { endpointPort = it; endpointMode = WarpMasqueConfig.ENDPOINT_MODE_CUSTOM },
                        placeholder = WarpMasqueConfig.DEFAULT_PORT.toString(),
                        maxLines = 1,
                        keyboardType = KeyboardType.Number,
                    )
                }
                item {
                    FormDropdownField(
                        label = "WARP endpoint mode",
                        value = endpointMode,
                        options = listOf(WarpMasqueConfig.ENDPOINT_MODE_AUTO, WarpMasqueConfig.ENDPOINT_MODE_CUSTOM),
                        onValueChange = { endpointMode = it },
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
                        region = psiphonRegion,
                        onEnabledChange = { psiphon = it },
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
                                warpMasqueEndpointMode = endpointMode,
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
                if (editGuid.isNotBlank() && !isRunning) {
                    item {
                        Button(
                            onClick = { showDelete = true },
                            modifier = Modifier.padding(16.dp),
                        ) { Text(stringResource(R.string.menu_item_del_config)) }
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
        psiphonRegion: String,
        psiphonMode: String,
        psiphonCdnIps: String,
        psiphonCdnSni: String,
        psiphonCdnSets: String,
        desyncProfile: String,
        desyncArgs: String,
        endpointMode: String,
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
            this.warpMasqueEndpointMode = endpointMode
            this.warpMasqueDeviceName = deviceName.trim().ifBlank { WarpMasqueConfig.randomDeviceName() }
            this.warpMasqueSni = sni.trim().ifBlank { WarpMasqueConfig.DEFAULT_SNI }
            this.warpMasqueDns = dns.trim()
            this.warpMasqueHttp2Enabled = http2Enabled
            this.warpMasqueSocksBind = this.warpMasqueSocksBind
                ?.trim()?.takeIf { it.isNotBlank() } ?: WarpMasqueConfig.DEFAULT_SOCKS_BIND
            this.warpMasqueSocksPort = this.warpMasqueSocksPort
                ?.coerceIn(1, 65535) ?: WarpMasqueConfig.DEFAULT_SOCKS_PORT
            this.warpMasqueConfigJson = this.warpMasqueConfigJson
                ?.trim()?.takeIf { it.isNotBlank() }
            this.psiphonEnabled = psiphon
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

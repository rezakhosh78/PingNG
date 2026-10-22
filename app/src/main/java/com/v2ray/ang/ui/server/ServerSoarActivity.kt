package com.v2ray.ang.ui.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.core.DnsFullConfigCodec
import com.v2ray.ang.core.SoarAdvancedSettings
import com.v2ray.ang.core.SoarBridge
import com.v2ray.ang.core.SoarLogFormatter
import com.v2ray.ang.core.SoarSettingField
import com.v2ray.ang.core.SoarSettings
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay

class ServerSoarActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.SOCKS

    @Composable
    override fun ScreenContent() {
        var profileRevision by rememberSaveable { mutableStateOf(0) }
        var remark by rememberSaveable(profileRevision) { mutableStateOf(initialConfig.remarks.ifBlank { SoarBridge.LABEL }) }
        var zone by rememberSaveable(profileRevision) { mutableStateOf(initialConfig.soarZone.orEmpty().ifBlank { initialConfig.masterDnsDomain.orEmpty() }) }
        var serverKey by rememberSaveable(profileRevision) { mutableStateOf(initialConfig.soarServerKey.orEmpty().ifBlank { initialConfig.masterDnsEncryptionKey.orEmpty() }) }
        val initialResolvers = remember(profileRevision) {
            SoarSettings.normalizeResolvers(initialConfig.soarResolvers.orEmpty().ifBlank { initialConfig.masterDnsResolvers.orEmpty() })
        }
        var resolvers by rememberSaveable(profileRevision) {
            mutableStateOf(initialResolvers)
        }
        var discovery by rememberSaveable(profileRevision) {
            mutableStateOf(SoarSettings.effectiveDiscoveryEnabled(initialResolvers, initialConfig.soarDiscoveryEnabled))
        }
        var country by rememberSaveable(profileRevision) {
            mutableStateOf(
                SoarSettings.selectedDiscoveryCountry("", initialConfig.soarDiscoveryCountry),
            )
        }
        val hasManualResolvers = SoarSettings.resolverLines(resolvers).isNotEmpty()
        val activeDiscovery = SoarSettings.effectiveDiscoveryEnabled(resolvers, discovery)
        var numericDrafts by remember(profileRevision) { mutableStateOf(emptyMap<String, String>()) }
        LaunchedEffect(hasManualResolvers) {
            if (hasManualResolvers) {
                discovery = false
            }
        }
        var tcpMode by rememberSaveable(profileRevision) { mutableStateOf(initialConfig.soarTcpMode.orEmpty().ifBlank { "auto" }) }
        var advancedJson by rememberSaveable(profileRevision) { mutableStateOf(SoarSettings.encode(SoarSettings.decode(initialConfig.soarAdvanced))) }
        val psiphonState = rememberSaveable(profileRevision, saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig)
        }
        var selectedTab by rememberSaveable { mutableStateOf(0) }
        var logText by remember(editGuid) { mutableStateOf("") }
        val logScrollState = rememberScrollState()
        var followLatest by remember(editGuid) { mutableStateOf(true) }
        val isLogDragged by logScrollState.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(isLogDragged) { if (isLogDragged) followLatest = false }
        LaunchedEffect(logText, selectedTab, followLatest) {
            if (followLatest && selectedTab == 2) {
                androidx.compose.runtime.withFrameNanos { }
                logScrollState.scrollTo(logScrollState.maxValue)
            }
        }
        LaunchedEffect(editGuid, selectedTab) {
            if (selectedTab == 2) {
                while (true) {
                    logText = if (editGuid.isBlank()) "Save and connect this profile to create a log."
                        else SoarBridge.readLog(this@ServerSoarActivity, editGuid)
                    delay(1_000)
                }
            }
        }
        val logPresentation = remember(logText) { SoarLogFormatter.parse(logText) }
        val coloredLog = remember(logPresentation.details) {
            buildAnnotatedString {
                val lines = logPresentation.details.split('\n')
                lines.forEachIndexed { index, line ->
                    val color = when {
                        line.contains("SOAR_SOCKS_LISTENING") || line.contains("verified", ignoreCase = true) -> Color(0xFF43A047)
                        line.contains("failed", ignoreCase = true) || line.contains("error", ignoreCase = true) -> Color(0xFFE53935)
                        line.contains("WARN", ignoreCase = true) || line.contains("SOAR_DNS_SCAN") -> Color(0xFFFFA000)
                        else -> null
                    }
                    if (color == null) append(line) else withStyle(SpanStyle(color = color)) { append(line) }
                    if (index < lines.lastIndex) append('\n')
                }
            }
        }

        fun importText(uriText: String?, onImported: (String) -> Unit) {
            if (uriText == null) return
            runCatching {
                contentResolver.openInputStream(android.net.Uri.parse(uriText))?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(1_000_001)
                    var length = 0
                    while (length < buffer.size) {
                        val read = reader.read(buffer, length, buffer.size - length)
                        if (read <= 0) break
                        length += read
                    }
                    require(length <= 1_000_000) { "Resolver file is too large" }
                    String(buffer, 0, length)
                } ?: error("Could not open resolver file")
            }.onSuccess { text ->
                if (text.lineSequence().none { it.trim().isNotEmpty() && !it.trim().startsWith('#') }) {
                    toast("Resolver file contains no DNS addresses")
                } else {
                    onImported(text)
                    toast("DNS resolvers imported")
                }
            }.onFailure { toast("Could not import DNS resolvers: ${it.message.orEmpty()}") }
        }
        val importResolvers = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            importText(uri?.toString()) {
                resolvers = it
                if (SoarSettings.resolverLines(it).isNotEmpty()) {
                    discovery = false
                }
            }
        }
        val importDiscoveryResolvers = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            importText(uri?.toString()) { text ->
                advancedJson = SoarSettings.update(advancedJson, "extraDiscoveryResolvers", text)
            }
        }
        val importFullProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    DnsFullConfigCodec.decode(readTextDocument(uri), SoarBridge.LABEL)
                        ?: error("File is not a valid SoarDNS full config")
                }.onSuccess { profile ->
                    applyImportedDnsProfile(profile)
                    profileRevision++
                    selectedTab = 0
                    toast("SoarDNS profile imported")
                }.onFailure { toast("Could not import profile: ${it.message.orEmpty()}") }
            }
        }

        Scaffold(topBar = {
            AppTopBar(title = SoarBridge.LABEL, onBackClick = { finish() }, actions = {
                TextButton(onClick = { importFullProfile.launch(arrayOf("text/plain", "application/json")) }) {
                    Text(stringResource(R.string.action_import_profile))
                }
                TextButton(onClick = {
                    val normalizedZone = zone.trim().trimEnd('.')
                    if (!isValidZone(normalizedZone)) {
                        toast(getString(R.string.soar_invalid_zone))
                        return@TextButton
                    }
                    if (!isValidPublicKey(serverKey)) {
                        toast(getString(R.string.soar_invalid_key))
                        return@TextButton
                    }
                    val normalizedResolvers = SoarSettings.normalizeResolvers(resolvers)
                    val resolverLines = SoarSettings.resolverLines(normalizedResolvers)
                    if (resolverLines.any { !SoarSettings.isValidResolver(it) }) {
                        toast(getString(R.string.soar_invalid_resolver))
                        return@TextButton
                    }
                    val useDiscovery = SoarSettings.effectiveDiscoveryEnabled(normalizedResolvers, discovery)
                    if (resolverLines.isEmpty() && !useDiscovery) {
                        toast(getString(R.string.soar_need_resolver))
                        return@TextButton
                    }
                    val selectedCountry = SoarSettings.selectedDiscoveryCountry("", country)
                    if (selectedCountry != "Auto" && selectedCountry !in countryCodes) {
                        toast(getString(R.string.soar_invalid_country))
                        return@TextButton
                    }
                    val committedAdvancedJson = SoarSettings.applyNumericDrafts(
                        advancedJson,
                        numericDrafts,
                        validateDiscovery = useDiscovery,
                    )
                    if (committedAdvancedJson == null) {
                        toast(getString(R.string.soar_invalid_numeric_setting))
                        return@TextButton
                    }
                    val currentAdvanced = SoarSettings.decode(committedAdvancedJson)
                    SoarSettings.validationError(currentAdvanced, tcpMode, validateDiscovery = useDiscovery)?.let {
                        toast(it)
                        return@TextButton
                    }
                    val guid = editGuid.ifBlank { UUID.randomUUID().toString() }
                    val saved = MmkvManager.encodeServerConfig(guid, initialConfig.copy(
                        configType = EConfigType.SOCKS,
                        subscriptionId = initialConfig.subscriptionId.ifBlank { subscriptionId.orEmpty() },
                        remarks = remark.trim().ifBlank { SoarBridge.LABEL },
                        description = SoarBridge.LABEL,
                        server = "127.0.0.1",
                        serverPort = SoarBridge.PORT.toString(),
                        soarZone = normalizedZone,
                        soarServerKey = serverKey.trim(),
                        soarResolvers = normalizedResolvers.ifBlank { null },
                        soarDiscoveryEnabled = useDiscovery,
                        soarDiscoveryCountry = SoarSettings.storedDiscoveryCountry(normalizedResolvers, country),
                        soarTcpMode = tcpMode,
                        soarAdvanced = SoarSettings.encode(currentAdvanced),
                        psiphonEnabled = psiphonState.psiphonEnabled,
                        torEnabled = false,
                        psiphonRegion = psiphonState.psiphonRegion.ifBlank { "ANY" }.uppercase(Locale.ROOT),
                        psiphonMode = psiphonState.psiphonMode.ifBlank { "auto" }.lowercase(Locale.ROOT),
                        psiphonCdnIps = psiphonState.psiphonCdnIps.trim().ifBlank { null },
                        psiphonCdnSni = psiphonState.psiphonCdnSni.trim().ifBlank { null },
                        psiphonCdnSets = psiphonState.psiphonCdnSets.trim().ifBlank { null },
                        bepassEnabled = false,
                        pingNgProfile = "Off",
                    ))
                    toastSuccess(R.string.toast_success)
                    ProfileEditorResult.run { finishSaved(saved, restartService = isRunning) }
                }) { Text("Save") }
            })
        }) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                val tabs = listOf("Basic Setting", "Advance Setting", "Log")
                TabRow(selectedTabIndex = selectedTab.coerceIn(tabs.indices)) {
                    tabs.forEachIndexed { index, label ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                    }
                }
                when (selectedTab.coerceIn(tabs.indices)) {
                    0 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item {
                            FormDropdownField("Core", SoarBridge.LABEL, coreOptions, { selected ->
                                if (selected != SoarBridge.LABEL) switchDnsCore(selected, buildSoarDraft(
                                    targetCore = selected,
                                    remark = remark,
                                    zone = zone,
                                    serverKey = serverKey,
                                    resolvers = resolvers,
                                    discovery = discovery,
                                    country = country,
                                    tcpMode = tcpMode,
                                    advancedJson = advancedJson,
                                    psiphonState = psiphonState,
                                ))
                            })
                        }
                        item { FormTextField("Remark", remark, { remark = it }, maxLines = 1) }
                        item { FormTextField(stringResource(R.string.soar_zone), zone, { zone = it }, maxLines = 1, placeholder = "t.example.com") }
                        item { FormTextField(stringResource(R.string.soar_server_key), serverKey, { serverKey = it }, maxLines = 2) }
                        item {
                            Text("DNS resolvers", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        item {
                            FormTextField(
                                "DNS resolvers (one per line; port defaults to 53)", resolvers,
                                {
                                    resolvers = it
                                    if (SoarSettings.resolverLines(it).isNotEmpty()) {
                                        discovery = false
                                    }
                                }, maxLines = 8,
                                placeholder = "8.8.8.8\n1.1.1.1:53",
                            )
                        }
                        item {
                            TextButton(onClick = { importResolvers.launch("text/plain") }) { Text("Import DNS resolvers from file") }
                        }
                        item {
                            SettingsSwitchItem(
                                title = stringResource(R.string.soar_discovery),
                                summary = stringResource(R.string.soar_discovery_summary),
                                checked = activeDiscovery,
                                onCheckedChange = { discovery = it },
                                enabled = !hasManualResolvers,
                            )
                        }
                        item {
                            FormDropdownField(
                                label = stringResource(R.string.soar_discovery_country),
                                value = SoarSettings.selectedDiscoveryCountry("", country),
                                options = countryCodes,
                                onValueChange = { country = it },
                                enabled = !hasManualResolvers && activeDiscovery,
                            )
                        }
                        item {
                            Text("Resolver scan speed", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        itemsIndexed(SoarSettings.discoveryFields(advancedJson), key = { _, field -> field.key }) { _, field ->
                            when (field.kind) {
                                SoarSettingField.Kind.NUMBER -> Column {
                                    FormTextField(
                                        label = field.label,
                                        value = numericDrafts[field.key] ?: field.value,
                                        onValueChange = { raw ->
                                            numericDrafts = numericDrafts + (field.key to raw)
                                            if (raw.toIntOrNull() != null) {
                                                advancedJson = SoarSettings.update(advancedJson, field.key, raw)
                                            }
                                        },
                                        enabled = true,
                                        maxLines = 1,
                                        keyboardType = KeyboardType.Number,
                                        onFocusLost = {
                                            if (numericDrafts[field.key]?.toIntOrNull() != null) {
                                                numericDrafts = numericDrafts - field.key
                                            }
                                        },
                                    )
                                    Text(
                                        stringResource(resolverScanSummaryResource(field.key)),
                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                SoarSettingField.Kind.BOOLEAN -> SettingsSwitchItem(
                                    title = field.label,
                                    summary = stringResource(resolverScanSummaryResource(field.key)),
                                    checked = field.value == "true",
                                    onCheckedChange = {
                                        advancedJson = SoarSettings.update(advancedJson, field.key, it.toString())
                                    },
                                    enabled = true,
                                )
                                SoarSettingField.Kind.STRING -> Unit
                            }
                        }
                        item {
                            PsiphonEditorFields(
                                enabled = psiphonState.psiphonEnabled,
                                torEnabled = false,
                                region = psiphonState.psiphonRegion,
                                onEnabledChange = { psiphonState.psiphonEnabled = it },
                                onTorEnabledChange = {},
                                onRegionChange = { psiphonState.psiphonRegion = it },
                                enabledTitle = stringResource(R.string.soar_psiphon_title),
                                torEnabledTitle = "Tor Over SoarDNS",
                                hintResId = R.string.soar_psiphon_hint,
                                mode = psiphonState.psiphonMode,
                                onModeChange = { psiphonState.psiphonMode = it },
                                cdnIps = psiphonState.psiphonCdnIps,
                                onCdnIpsChange = { psiphonState.psiphonCdnIps = it },
                                cdnSni = psiphonState.psiphonCdnSni,
                                onCdnSniChange = { psiphonState.psiphonCdnSni = it },
                                cdnSets = psiphonState.psiphonCdnSets,
                                onCdnSetsChange = { psiphonState.psiphonCdnSets = it },
                            )
                        }
                    }
                    1 -> {
                        val fields = SoarSettings.fields(advancedJson)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item {
                                FormDropdownField("DNS-over-TCP policy", tcpMode, listOf("auto", "on", "only", "off"), { tcpMode = it })
                            }
                            itemsIndexed(fields, key = { _, field -> field.key }) { index, field ->
                                val enabled = !hasManualResolvers || field.section != "Resolver discovery"
                                Column {
                                    if (index == 0 || field.section != fields[index - 1].section) {
                                        Text(field.section, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                                    }
                                    when (field.kind) {
                                        SoarSettingField.Kind.BOOLEAN -> SettingsSwitchItem(
                                            title = field.label,
                                            checked = field.value == "true",
                                            onCheckedChange = { advancedJson = SoarSettings.update(advancedJson, field.key, it.toString()) },
                                            enabled = enabled,
                                        )
                                        SoarSettingField.Kind.NUMBER -> FormTextField(
                                            label = field.label,
                                            value = numericDrafts[field.key] ?: field.value,
                                            onValueChange = { raw ->
                                                numericDrafts = numericDrafts + (field.key to raw)
                                                if (raw.toIntOrNull() != null) {
                                                    advancedJson = SoarSettings.update(advancedJson, field.key, raw)
                                                }
                                            },
                                            enabled = enabled,
                                            maxLines = 1,
                                            keyboardType = KeyboardType.Number,
                                            onFocusLost = {
                                                if (numericDrafts[field.key]?.toIntOrNull() != null) {
                                                    numericDrafts = numericDrafts - field.key
                                                }
                                            },
                                        )
                                        SoarSettingField.Kind.STRING -> {
                                            FormTextField(
                                                field.label,
                                                field.value,
                                                { advancedJson = SoarSettings.update(advancedJson, field.key, it) },
                                                enabled = enabled,
                                                maxLines = 8,
                                            )
                                            TextButton(
                                                onClick = { importDiscoveryResolvers.launch("text/plain") },
                                                enabled = enabled,
                                            ) {
                                                Text("Import extra discovery resolvers")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        logPresentation.scans.forEach { scan ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.soar_log_resolver_scan),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        stringResource(
                                            if (scan.discovery) R.string.soar_log_exploratory_discovery
                                            else R.string.soar_log_manual_resolvers,
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(stringResource(R.string.soar_log_checked))
                                        Text(
                                            "${scan.checked} / ${scan.total}",
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(stringResource(R.string.soar_log_verified))
                                        Text(scan.verified.toString(), fontWeight = FontWeight.SemiBold)
                                    }
                                    scan.time.takeIf(String::isNotBlank)?.let { time ->
                                        Text(
                                            stringResource(R.string.soar_log_updated, time),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Row {
                            TextButton(onClick = {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("SoarDNS log", logText))
                                toast("Log copied")
                            }) { Text("Copy log") }
                            TextButton(onClick = {
                                SoarBridge.clearLog(this@ServerSoarActivity, editGuid)
                                logText = ""
                                followLatest = true
                            }) { Text("Clear log") }
                            if (!followLatest) TextButton(onClick = { followLatest = true }) { Text("Latest") }
                        }
                        SelectionContainer {
                            Text(
                                coloredLog,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                                    .pointerInput(editGuid) {
                                        awaitPointerEventScope {
                                            while (true) if (awaitPointerEvent().type == PointerEventType.Scroll) followLatest = false
                                        }
                                    }
                                    .verticalScroll(logScrollState)
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildSoarDraft(
        targetCore: String,
        remark: String,
        zone: String,
        serverKey: String,
        resolvers: String,
        discovery: Boolean,
        country: String,
        tcpMode: String,
        advancedJson: String,
        psiphonState: ServerUiState,
    ): ProfileItem {
        val dnsResolvers = SoarSettings.normalizeResolvers(resolvers)
        val useDiscovery = SoarSettings.effectiveDiscoveryEnabled(dnsResolvers, discovery)
        return initialConfig.copy(
            configType = EConfigType.SOCKS,
            subscriptionId = initialConfig.subscriptionId.ifBlank { subscriptionId.orEmpty() },
            remarks = remark.trim().ifBlank { SoarBridge.LABEL },
            description = targetCore,
            server = "127.0.0.1",
            serverPort = MasterDnsBridge.PORT.toString(),
            masterDnsDomain = zone.trim().trimEnd('.'),
            masterDnsEncryptionKey = serverKey.trim(),
            masterDnsMethod = initialConfig.masterDnsMethod ?: 1,
            masterDnsResolvers = dnsResolvers.ifBlank { null },
            masterDnsAdvanced = initialConfig.masterDnsAdvanced,
            dnsTunnelTransport = initialConfig.dnsTunnelTransport ?: "udp",
            dnsTunnelMode = "SOCKS5",
            psiphonEnabled = psiphonState.psiphonEnabled,
            torEnabled = false,
            psiphonRegion = psiphonState.psiphonRegion.ifBlank { "ANY" }.uppercase(Locale.ROOT),
            psiphonMode = psiphonState.psiphonMode.ifBlank { "auto" }.lowercase(Locale.ROOT),
            psiphonCdnIps = psiphonState.psiphonCdnIps.trim().ifBlank { null },
            psiphonCdnSni = psiphonState.psiphonCdnSni.trim().ifBlank { null },
            psiphonCdnSets = psiphonState.psiphonCdnSets.trim().ifBlank { null },
            soarZone = zone.trim().trimEnd('.'),
            soarServerKey = serverKey.trim(),
            soarResolvers = dnsResolvers.ifBlank { null },
            soarDiscoveryEnabled = useDiscovery,
            soarDiscoveryCountry = SoarSettings.storedDiscoveryCountry(dnsResolvers, country),
            soarTcpMode = tcpMode,
            soarAdvanced = advancedJson,
        )
    }

    private fun isValidZone(value: String): Boolean {
        if (value.length !in 3..253 || !value.contains('.')) return false
        return value.split('.').all { label ->
            label.length in 1..63 && label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
        }
    }

    private fun isValidPublicKey(value: String): Boolean {
        val text = value.trim()
        if (text.matches(Regex("[A-Fa-f0-9]{64}"))) return true
        val decoded = runCatching { Base64.decode(text, Base64.DEFAULT) }
            .recoverCatching { Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP) }
            .getOrNull()
        return decoded?.size == 32
    }

    companion object {
        private val coreOptions = listOf(MasterDnsBridge.UI_LABEL, SoarBridge.LABEL, MasterDnsBridge.DNSTT)
        private val countryCodes = listOf("Auto") + Locale.getISOCountries().sorted()
    }
}

private fun resolverScanSummaryResource(key: String): Int = when (key) {
    "discoveryConcurrency" -> R.string.soar_discovery_concurrency_summary
    "discoveryPaceMs" -> R.string.soar_discovery_pace_summary
    "discoveryWant" -> R.string.soar_discovery_want_summary
    else -> R.string.soar_discovery_cache_summary
}

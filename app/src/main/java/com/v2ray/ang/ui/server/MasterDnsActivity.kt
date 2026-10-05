package com.v2ray.ang.ui.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.core.MasterDnsSettings
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import java.util.UUID
import kotlinx.coroutines.delay

class MasterDnsActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.SOCKS
    private val methods = listOf("None", "XOR", "ChaCha20", "AES-128-GCM", "AES-192-GCM", "AES-256-GCM")
    private val balancingOptions = listOf(
        "1 - Random", "2 - Round Robin", "3 - Least Loss", "4 - Lowest Latency",
        "5 - Hybrid Score", "6 - Loss Then Latency", "7 - Least Loss Top Random", "8 - Least Loss Top Round Robin",
    )
    private val compressionOptions = listOf("0 - OFF", "1 - ZSTD", "2 - LZ4", "3 - ZLIB")

    @Composable
    override fun ScreenContent() {
        var engine by rememberSaveable { mutableStateOf(
            when (val selected = intent.getStringExtra("dnsEngine") ?: initialConfig.description) {
                MasterDnsBridge.DNSTT -> MasterDnsBridge.DNSTT
                else -> MasterDnsBridge.LABEL
            }) }
        var transport by rememberSaveable { mutableStateOf(initialConfig.dnsTunnelTransport ?: "udp") }
        var tunnelMode by rememberSaveable { mutableStateOf(initialConfig.dnsTunnelMode ?: "SOCKS5") }
        var sshUsername by rememberSaveable { mutableStateOf(initialConfig.dnsTunnelSshUsername.orEmpty()) }
        var sshPassword by rememberSaveable { mutableStateOf(initialConfig.dnsTunnelSshPassword.orEmpty()) }
        var sshKeepaliveSeconds by rememberSaveable {
            mutableStateOf((initialConfig.dnsTunnelSshKeepaliveSeconds ?: 6).toString())
        }
        var sshHost by rememberSaveable { mutableStateOf(initialConfig.dnsTunnelSshHost.orEmpty()) }
        var sshPort by rememberSaveable { mutableStateOf((initialConfig.dnsTunnelSshPort ?: 22).toString()) }
        var remark by rememberSaveable {
            mutableStateOf(when {
                initialConfig.remarks.isBlank() -> if (engine == MasterDnsBridge.LABEL) MasterDnsBridge.UI_LABEL else engine
                initialConfig.remarks == "StormDNS" -> MasterDnsBridge.UI_LABEL
                else -> initialConfig.remarks
            })
        }
        var domain by rememberSaveable { mutableStateOf(initialConfig.masterDnsDomain.orEmpty()) }
        var key by rememberSaveable { mutableStateOf(initialConfig.masterDnsEncryptionKey.orEmpty()) }
        var method by rememberSaveable { mutableStateOf(initialConfig.masterDnsMethod ?: 1) }
        val defaultConfig = remember {
            assets.open("stormdns_client_config.toml").bufferedReader().use { it.readText() }
        }
        var advanced by rememberSaveable {
            mutableStateOf(MasterDnsSettings.withDefaults(defaultConfig, initialConfig.masterDnsAdvanced))
        }
        val defaultResolvers = remember {
            assets.open("masterdns_client_resolvers.txt").bufferedReader().use { it.readText() }
        }
        var resolvers by rememberSaveable {
            val stored = initialConfig.masterDnsResolvers
            mutableStateOf(stored ?: if (engine == MasterDnsBridge.DNSTT) "8.8.8.8:53" else defaultResolvers)
        }
        var reuseValidResolvers by rememberSaveable { mutableStateOf(initialConfig.masterDnsReuseValidResolvers) }
        val importResolvers = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(1_000_001)
                        var length = 0
                        while (length < buffer.size) {
                            val read = reader.read(buffer, length, buffer.size - length)
                            if (read <= 0) break
                            length += read
                        }
                        require(length <= 1_000_000) { "TXT file is too large" }
                        String(buffer, 0, length)
                    } ?: error("Could not open TXT file")
                }.onSuccess { text ->
                    if (text.lineSequence().none { it.trim().isNotEmpty() && !it.trim().startsWith('#') }) {
                        toast("TXT file contains no resolvers")
                    } else {
                        resolvers = text
                        toast("DNS resolvers imported")
                    }
                }.onFailure { toast("Could not import DNS resolvers: ${it.message.orEmpty()}") }
            }
        }
        var psiphon by rememberSaveable { mutableStateOf(initialConfig.psiphonEnabled) }
        var psiphonRegion by rememberSaveable { mutableStateOf(initialConfig.psiphonRegion ?: "ANY") }
        var psiphonMode by rememberSaveable { mutableStateOf(initialConfig.psiphonMode ?: "auto") }
        var psiphonCdnIps by rememberSaveable { mutableStateOf(initialConfig.psiphonCdnIps.orEmpty()) }
        var psiphonCdnSni by rememberSaveable { mutableStateOf(initialConfig.psiphonCdnSni.orEmpty()) }
        var psiphonCdnSets by rememberSaveable { mutableStateOf(initialConfig.psiphonCdnSets.orEmpty()) }
        var selectedTab by rememberSaveable { mutableStateOf(0) }
        var logText by remember(editGuid, engine) { mutableStateOf("") }
        val coloredLog = remember(logText) {
            buildAnnotatedString {
                val lines = logText.split('\n')
                lines.forEachIndexed { index, line ->
                    val color = when {
                        line.contains("✅") || line.contains("وصل شد") ||
                            line.contains("TCP forwarding established", ignoreCase = true) -> Color(0xFF43A047)
                        line.contains("❌") || line.contains("failed", ignoreCase = true) ||
                            line.contains("Exception", ignoreCase = true) -> Color(0xFFE53935)
                        line.contains("⚠") || line.contains("⏳") ||
                            line.contains("WARN", ignoreCase = true) -> Color(0xFFFFA000)
                        else -> null
                    }
                    if (color == null) append(line) else withStyle(SpanStyle(color = color)) { append(line) }
                    if (index < lines.lastIndex) append('\n')
                }
            }
        }
        val logScrollState = rememberScrollState()
        var followLatest by remember(editGuid, engine) { mutableStateOf(true) }
        val isLogDragged by logScrollState.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(isLogDragged) { if (isLogDragged) followLatest = false }
        LaunchedEffect(logText, selectedTab, followLatest) {
            if (followLatest && selectedTab == (if (engine == MasterDnsBridge.LABEL) 2 else 1)) {
                androidx.compose.runtime.withFrameNanos { }
                logScrollState.scrollTo(logScrollState.maxValue)
            }
        }
        LaunchedEffect(editGuid, engine, selectedTab) {
            if (selectedTab == (if (engine == MasterDnsBridge.LABEL) 2 else 1)) {
                while (true) {
                    logText = if (editGuid.isBlank()) "Save and connect this profile to create a log."
                        else MasterDnsBridge.readLog(this@MasterDnsActivity, editGuid, engine)
                    delay(1_000)
                }
            }
        }
        Scaffold(topBar = {
            AppTopBar(title = if (engine == MasterDnsBridge.LABEL) MasterDnsBridge.UI_LABEL else engine, onBackClick = { finish() }, actions = {
                TextButton(onClick = {
                    if (remark.isBlank() || domain.isBlank() || key.isBlank() ||
                        domain.any { it.isWhitespace() || it == '"' || it == '\\' }) {
                        toast("Enter a remark, valid domain, and encryption key")
                        return@TextButton
                    }
                    if (engine == MasterDnsBridge.DNSTT && runCatching {
                            com.v2ray.ang.core.DnsTunnelArguments.dnstt("core", transport, resolvers, key, domain, MasterDnsBridge.PORT)
                        }.onFailure { toast(it.message.orEmpty()) }.isFailure) return@TextButton
                    if (engine == MasterDnsBridge.DNSTT && tunnelMode == "SSH" &&
                        (sshUsername.isBlank() || sshPassword.isBlank())) {
                        toast("Enter the SSH account username and password")
                        return@TextButton
                    }
                    val keepaliveSeconds = sshKeepaliveSeconds.toIntOrNull()
                    if (engine == MasterDnsBridge.DNSTT && tunnelMode == "SSH" &&
                        (keepaliveSeconds == null || keepaliveSeconds !in 5..300)) {
                        toast("SSH keepalive interval must be between 5 and 300 seconds")
                        return@TextButton
                    }
                    val parallelism = MasterDnsSettings.value(advanced, "MTU_TEST_PARALLELISM")?.toIntOrNull()
                    if (engine == MasterDnsBridge.LABEL && (parallelism == null || parallelism !in 1..10000)) {
                        toast("MTU test parallelism must be between 1 and 10000")
                        return@TextButton
                    }
                    val defaults = MasterDnsSettings.fields(defaultConfig).associateBy { it.key }
                    val invalid = MasterDnsSettings.fields(advanced).firstOrNull { field ->
                        !MasterDnsSettings.valid(field) ||
                            (defaults[field.key]?.value?.toLongOrNull() != null &&
                                field.value.toLongOrNull() == null)
                    }
                    if (engine == MasterDnsBridge.LABEL && invalid != null) {
                        toast("Invalid value for ${invalid.key}")
                        return@TextButton
                    }
                    val guid = editGuid.ifBlank { UUID.randomUUID().toString() }
                    val saved = MmkvManager.encodeServerConfig(guid, initialConfig.copy(
                        configType = EConfigType.SOCKS,
                        subscriptionId = initialConfig.subscriptionId.ifBlank { subscriptionId.orEmpty() },
                        remarks = remark.trim(),
                        description = engine,
                        dnsTunnelTransport = transport,
                        dnsTunnelMode = tunnelMode,
                        dnsTunnelSshUsername = sshUsername,
                        dnsTunnelSshPassword = sshPassword,
                        dnsTunnelSshKeepaliveSeconds = keepaliveSeconds ?: 6,
                        dnsTunnelSshHost = sshHost,
                        dnsTunnelSshPort = sshPort.toIntOrNull(),
                        server = "127.0.0.1",
                        serverPort = if (engine == MasterDnsBridge.DNSTT && tunnelMode == "SSH")
                            MasterDnsBridge.SSH_SOCKS_PORT.toString() else MasterDnsBridge.PORT.toString(),
                        masterDnsDomain = domain.trim(),
                        masterDnsEncryptionKey = key,
                        masterDnsMethod = method,
                        masterDnsAdvanced = advanced,
                        masterDnsResolvers = resolvers,
                        masterDnsReuseValidResolvers = reuseValidResolvers,
                        psiphonEnabled = psiphon,
                        psiphonRegion = psiphonRegion.ifBlank { "ANY" }.uppercase(),
                        psiphonMode = psiphonMode.ifBlank { "auto" }.lowercase(),
                        psiphonCdnIps = psiphonCdnIps.trim().ifBlank { null },
                        psiphonCdnSni = psiphonCdnSni.trim().ifBlank { null },
                        psiphonCdnSets = psiphonCdnSets.trim().ifBlank { null },
                    ))
                    toastSuccess(R.string.toast_success)
                    ProfileEditorResult.run { finishSaved(saved, restartService = isRunning) }
                }) { Text("Save") }
            })
        }) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                val tabs = if (engine == MasterDnsBridge.LABEL)
                    listOf("Basic Setting", "Advance Setting", "Log") else listOf("Setting", "Log")
                val tabIndex = selectedTab.coerceIn(tabs.indices)
                TabRow(selectedTabIndex = tabIndex) {
                    tabs.forEachIndexed { index, label ->
                        Tab(selected = tabIndex == index, onClick = { selectedTab = index }, text = { Text(label) })
                    }
                }
                if (tabIndex == tabs.lastIndex) {
                    Row {
                    TextButton(onClick = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("$engine log", logText))
                        toast("Log copied")
                    }) { Text("Copy log") }
                    TextButton(onClick = {
                        MasterDnsBridge.clearLog(this@MasterDnsActivity, editGuid, engine)
                        logText = ""
                        followLatest = true
                    }) { Text("Clear log") }
                    if (!followLatest) TextButton(onClick = { followLatest = true }) { Text("Latest") }
                    }
                    SelectionContainer {
                        Text(coloredLog, modifier = Modifier.fillMaxSize()
                            .pointerInput(editGuid, engine) {
                                awaitPointerEventScope {
                                    while (true) {
                                        if (awaitPointerEvent().type == PointerEventType.Scroll) followLatest = false
                                    }
                                }
                            }
                            .verticalScroll(logScrollState).padding(12.dp))
                    }
                } else {
                val showAdvanced = engine == MasterDnsBridge.LABEL && tabIndex == 1
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!showAdvanced) {
                        item { FormDropdownField("Core", engine, listOf(MasterDnsBridge.UI_LABEL, MasterDnsBridge.DNSTT), {
                            engine = if (it == MasterDnsBridge.UI_LABEL) MasterDnsBridge.LABEL else it
                            selectedTab = 0
                            if (it == MasterDnsBridge.DNSTT && resolvers.lineSequence().count { line -> line.isNotBlank() && !line.trim().startsWith('#') } != 1)
                                resolvers = "8.8.8.8:53"
                        }) }
                        if (engine == MasterDnsBridge.DNSTT) {
                            item { FormDropdownField("DNS transport", transport, listOf("udp", "doh", "dot"), { transport = it }) }
                            item { FormDropdownField("Remote endpoint", tunnelMode, listOf("SOCKS5", "SSH"), { tunnelMode = it }) }
                            if (tunnelMode == "SSH") {
                                item { FormTextField("SSH username", sshUsername, { sshUsername = it }, maxLines = 1) }
                                item { FormTextField("SSH password", sshPassword, { sshPassword = it }, maxLines = 1) }
                                item {
                                    FormTextField(
                                        "SSH keepalive interval (seconds)", sshKeepaliveSeconds,
                                        { sshKeepaliveSeconds = it }, maxLines = 1,
                                        keyboardType = KeyboardType.Number,
                                    )
                                }
                            }
                        }
                        item { FormTextField("Remark", remark, { remark = it }, maxLines = 1) }
                        item { FormTextField("Domain", domain, { domain = it }, maxLines = 1) }
                        item { FormTextField(if (engine == MasterDnsBridge.DNSTT) "Server public key (hex)" else "Encryption Key", key, { key = it }, maxLines = 1) }
                        if (engine == MasterDnsBridge.LABEL) item {
                            FormDropdownField("Method", methods[method.coerceIn(0, 5)], methods,
                                { method = methods.indexOf(it).coerceAtLeast(0) })
                        }
                        if (engine == MasterDnsBridge.LABEL) item {
                            FormTextField("MTU_TEST_PARALLELISM",
                                MasterDnsSettings.value(advanced, "MTU_TEST_PARALLELISM").orEmpty(),
                                { advanced = MasterDnsSettings.put(advanced, "MTU_TEST_PARALLELISM",
                                    it, MasterDnsSettings.Kind.NUMBER) },
                                maxLines = 1, keyboardType = KeyboardType.Number)
                        }
                        if (engine == MasterDnsBridge.LABEL) item {
                            SettingsSwitchItem(
                                title = "Fast",
                                summary = "On: scan once, then use saved valid resolvers. Off: scan every time.",
                                checked = reuseValidResolvers,
                                onCheckedChange = { reuseValidResolvers = it },
                            )
                        }
                        item {
                            Text("DNS resolvers", modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        item { FormTextField("DNS resolvers (one per line)", resolvers, { resolvers = it }, maxLines = 8) }
                        if (engine != MasterDnsBridge.DNSTT) item {
                            TextButton(onClick = { importResolvers.launch("text/plain") }) {
                                Text("Import DNS resolvers from TXT")
                            }
                        }
                        item {
                            PsiphonEditorFields(
                                enabled = psiphon,
                                region = psiphonRegion,
                                onEnabledChange = { psiphon = it },
                                onRegionChange = { psiphonRegion = it },
                                enabledTitle = "Psiphon Over ${if (engine == MasterDnsBridge.LABEL) MasterDnsBridge.UI_LABEL else engine}",
                                hintResId = R.string.pingng_psiphon_masterdns_hint,
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
                    } else {
                        val fields = MasterDnsSettings.fields(advanced).filterNot { it.key in setOf(
                            "STARTUP_MODE", "LOG_BASED_MTU_VERIFY", "LOG_SCAN_MAX_DAYS", "LOG_SCAN_MAX_RESOLVERS"
                        ) }
                        itemsIndexed(fields, key = { _, field -> field.key }) { index, field ->
                            Column {
                                if (index == 0 || field.section != fields[index - 1].section) {
                                    Text(field.section, modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.titleMedium)
                                }
                                when {
                                    field.kind == MasterDnsSettings.Kind.BOOLEAN -> SettingsSwitchItem(
                                        title = field.key.replace('_', ' '),
                                        checked = field.value == "true",
                                        onCheckedChange = { checked ->
                                            advanced = MasterDnsSettings.put(advanced, field.key,
                                                checked.toString(), field.kind)
                                        },
                                    )
                                    field.key == "RESOLVER_BALANCING_STRATEGY" ||
                                        field.key == "UPLOAD_COMPRESSION_TYPE" ||
                                        field.key == "DOWNLOAD_COMPRESSION_TYPE" -> {
                                        val options = if (field.key == "RESOLVER_BALANCING_STRATEGY")
                                            balancingOptions else compressionOptions
                                        FormDropdownField(
                                            field.key.replace('_', ' '),
                                            options.firstOrNull { it.substringBefore(' ') == field.value } ?: field.value,
                                            options,
                                            { selection ->
                                                advanced = MasterDnsSettings.put(advanced, field.key,
                                                    selection.substringBefore(' '), field.kind)
                                            },
                                        )
                                    }
                                    else -> FormTextField(
                                        field.key.replace('_', ' '), field.value,
                                        { advanced = MasterDnsSettings.put(advanced, field.key, it, field.kind) },
                                        maxLines = if (field.kind == MasterDnsSettings.Kind.STRING) 2 else 1,
                                        keyboardType = if (field.kind == MasterDnsSettings.Kind.NUMBER)
                                            KeyboardType.Decimal else KeyboardType.Text,
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

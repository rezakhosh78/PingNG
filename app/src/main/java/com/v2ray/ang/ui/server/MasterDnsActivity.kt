package com.v2ray.ang.ui.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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

class MasterDnsActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.SOCKS
    private val methods = listOf("None", "XOR", "ChaCha20", "AES-128-GCM", "AES-192-GCM", "AES-256-GCM")
    private val balancingOptions = listOf(
        "0 - Round Robin", "1 - Random", "2 - Round Robin", "3 - Least Loss", "4 - Lowest Latency",
        "5 - Hybrid Score", "6 - Loss Then Latency", "7 - Least Loss Top Random",
        "8 - Least Loss Top Round Robin",
    )
    private val compressionOptions = listOf("0 - OFF", "1 - ZSTD", "2 - LZ4", "3 - ZLIB")

    @Composable
    override fun ScreenContent() {
        var remark by rememberSaveable { mutableStateOf(initialConfig.remarks.ifBlank { "MasterDNS" }) }
        var domain by rememberSaveable { mutableStateOf(initialConfig.masterDnsDomain.orEmpty()) }
        var key by rememberSaveable { mutableStateOf(initialConfig.masterDnsEncryptionKey.orEmpty()) }
        var method by rememberSaveable { mutableStateOf(initialConfig.masterDnsMethod ?: 1) }
        val defaultConfig = remember {
            assets.open("masterdns_client_config.toml").bufferedReader().use { it.readText() }
        }
        var advanced by rememberSaveable {
            val previousDefault = defaultConfig.replace(
                "MTU_TEST_PARALLELISM = 250", "MTU_TEST_PARALLELISM = 32")
            mutableStateOf(initialConfig.masterDnsAdvanced
                ?.takeUnless { it == previousDefault } ?: defaultConfig)
        }
        val defaultResolvers = remember {
            assets.open("masterdns_client_resolvers.txt").bufferedReader().use { it.readText() }
        }
        var resolvers by rememberSaveable {
            val stored = initialConfig.masterDnsResolvers
            mutableStateOf(if (stored?.lineSequence()?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.startsWith('#') }?.toList() == listOf("8.8.8.8"))
                defaultResolvers else stored ?: defaultResolvers)
        }
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
        var advancedTab by rememberSaveable { mutableStateOf(false) }
        Scaffold(topBar = {
            AppTopBar(title = "MasterDNS", onBackClick = { finish() }, actions = {
                TextButton(onClick = {
                    if (remark.isBlank() || domain.isBlank() || key.isBlank() ||
                        domain.any { it.isWhitespace() || it == '"' || it == '\\' }) {
                        toast("Enter a remark, valid domain, and encryption key")
                        return@TextButton
                    }
                    val parallelism = MasterDnsSettings.value(advanced, "MTU_TEST_PARALLELISM")?.toIntOrNull()
                    if (parallelism == null || parallelism !in 1..10000) {
                        toast("MTU test parallelism must be between 1 and 10000")
                        return@TextButton
                    }
                    val defaults = MasterDnsSettings.fields(defaultConfig).associateBy { it.key }
                    val invalid = MasterDnsSettings.fields(advanced).firstOrNull { field ->
                        !MasterDnsSettings.valid(field) ||
                            (defaults[field.key]?.value?.toLongOrNull() != null &&
                                field.value.toLongOrNull() == null)
                    }
                    if (invalid != null) {
                        toast("Invalid value for ${invalid.key}")
                        return@TextButton
                    }
                    val guid = editGuid.ifBlank { UUID.randomUUID().toString() }
                    val saved = MmkvManager.encodeServerConfig(guid, initialConfig.copy(
                        configType = EConfigType.SOCKS,
                        subscriptionId = initialConfig.subscriptionId.ifBlank { subscriptionId.orEmpty() },
                        remarks = remark.trim(),
                        description = MasterDnsBridge.LABEL,
                        server = "127.0.0.1",
                        serverPort = MasterDnsBridge.PORT.toString(),
                        masterDnsDomain = domain.trim(),
                        masterDnsEncryptionKey = key,
                        masterDnsMethod = method,
                        masterDnsAdvanced = advanced,
                        masterDnsResolvers = resolvers,
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
                TabRow(selectedTabIndex = if (advancedTab) 1 else 0) {
                    Tab(selected = !advancedTab, onClick = { advancedTab = false }, text = { Text("Basic Setting") })
                    Tab(selected = advancedTab, onClick = { advancedTab = true }, text = { Text("Advance Setting") })
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!advancedTab) {
                        item { FormTextField("Remark", remark, { remark = it }, maxLines = 1) }
                        item { FormTextField("Domain", domain, { domain = it }, maxLines = 1) }
                        item { FormTextField("Encryption Key", key, { key = it }, maxLines = 1) }
                        item {
                            FormDropdownField("Method", methods[method.coerceIn(0, 5)], methods,
                                { method = methods.indexOf(it).coerceAtLeast(0) })
                        }
                        item {
                            FormTextField("MTU_TEST_PARALLELISM_RESOLVER",
                                MasterDnsSettings.value(advanced, "MTU_TEST_PARALLELISM").orEmpty(),
                                { advanced = MasterDnsSettings.put(advanced, "MTU_TEST_PARALLELISM",
                                    it, MasterDnsSettings.Kind.NUMBER) },
                                maxLines = 1, keyboardType = KeyboardType.Number)
                        }
                        item {
                            Text("DNS resolvers", modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        item { FormTextField("DNS resolvers (one per line)", resolvers, { resolvers = it }, maxLines = 8) }
                        item {
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
                                enabledTitle = "Psiphon Over MasterDNS",
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
                        val fields = MasterDnsSettings.fields(advanced)
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

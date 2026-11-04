package com.v2ray.ang.ui.server

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.OpenVpnProfileCodec
import com.v2ray.ang.core.OpenVpnShareCodec
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.OpenVpnCredentialStore
import com.v2ray.ang.handler.OpenVpnCredentials
import com.v2ray.ang.handler.SubscriptionProfileOverrides
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.server.ProfileEditorResult
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerOpenVpnActivity : BaseComponentActivity() {
    private val editGuid by lazy { intent.getStringExtra(EXTRA_GUID).orEmpty() }
    private val initialProfile by lazy {
        MmkvManager.decodeServerConfig(editGuid) ?: OpenVpnProfileCodec.createProfile(
            text = intent.getStringExtra(EXTRA_CONFIG_TEXT).orEmpty(),
            fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
            subscriptionId = intent.getStringExtra(EXTRA_SUBSCRIPTION_ID).orEmpty(),
        )
    }

    @Composable
    override fun ScreenContent() {
        val inlineCredentials = remember(editGuid) {
            OpenVpnProfileCodec.inlineUsernamePasswordCredentials(
                initialProfile.openVpnConfig.orEmpty(),
            )
        }
        var remarks by rememberSaveable(editGuid) { mutableStateOf(initialProfile.remarks) }
        var rawConfig by rememberSaveable(editGuid) {
            mutableStateOf(initialProfile.openVpnConfig.orEmpty())
        }
        var username by remember(editGuid) {
            mutableStateOf(inlineCredentials?.username.orEmpty())
        }
        var password by remember(editGuid) {
            mutableStateOf(inlineCredentials?.password.orEmpty())
        }
        var privateKeyPassphrase by remember(editGuid) { mutableStateOf("") }
        var externalPkiAlias by remember(editGuid) { mutableStateOf("") }
        var disableClientCertificate by remember(editGuid) { mutableStateOf(false) }
        var clientCertificatePem by remember(editGuid) { mutableStateOf("") }
        var privateKeyPem by remember(editGuid) { mutableStateOf("") }
        var credentialsLoaded by remember(editGuid) {
            mutableStateOf(editGuid.isBlank())
        }
        var showDelete by rememberSaveable(editGuid) { mutableStateOf(false) }
        LaunchedEffect(editGuid) {
            val credentials = withContext(Dispatchers.IO) {
                OpenVpnCredentialStore.load(applicationContext, editGuid)
            }
            credentials?.let {
                username = it.username.ifBlank { username }
                password = it.password.ifBlank { password }
                privateKeyPassphrase = it.privateKeyPassphrase
                externalPkiAlias = it.externalPkiAlias
                disableClientCertificate = it.disableClientCertificate
                clientCertificatePem = it.clientCertificatePem
                privateKeyPem = it.privateKeyPem
            }
            credentialsLoaded = true
        }
        val certificateFilePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val imported = try {
                        val content = withContext(Dispatchers.IO) {
                            readBoundedText(uri, 128 * 1024, R.string.openvpn_identity_file_invalid)
                        }
                        OpenVpnProfileCodec.readClientIdentityMaterial(content)
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        toast(error.message ?: getString(R.string.openvpn_identity_file_invalid))
                        return@launch
                    }
                    if (imported.certificatePem.isBlank() && imported.privateKeyPem.isBlank()) {
                        toast(R.string.openvpn_identity_file_invalid)
                    } else {
                        if (imported.certificatePem.isNotBlank()) {
                            clientCertificatePem = imported.certificatePem
                        }
                        if (imported.privateKeyPem.isNotBlank()) {
                            privateKeyPem = imported.privateKeyPem
                        }
                        if (clientCertificatePem.isNotBlank() && privateKeyPem.isNotBlank()) {
                            externalPkiAlias = ""
                        }
                        toast(R.string.openvpn_identity_imported)
                    }
                }
            }
        }
        val profileFilePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) lifecycleScope.launch {
                val imported = try {
                    val raw = withContext(Dispatchers.IO) { readProfileText(uri) }
                    val sharedProfile = OpenVpnShareCodec.parse(raw)
                    val config = sharedProfile?.openVpnConfig ?: raw
                    require(OpenVpnProfileCodec.looksLikeOpenVpn(config)) {
                        getString(R.string.openvpn_import_invalid)
                    }
                    config to sharedProfile?.remarks
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    toast(error.message ?: getString(R.string.openvpn_import_invalid))
                    return@launch
                }
                rawConfig = imported.first
                val embeddedCredentials =
                    OpenVpnProfileCodec.inlineUsernamePasswordCredentials(imported.first)
                username = embeddedCredentials?.username.orEmpty()
                password = embeddedCredentials?.password.orEmpty()
                remarks = imported.second?.takeIf { it.isNotBlank() }
                    ?: OpenVpnProfileCodec.detect(imported.first).remote.ifBlank { "OpenVPN" }
                privateKeyPassphrase = ""
                externalPkiAlias = ""
                disableClientCertificate = false
                clientCertificatePem = ""
                privateKeyPem = ""
                toastSuccess(R.string.toast_success)
            }
        }
        val fields = OpenVpnProfileCodec.editableFields(rawConfig)
            .mapIndexed { index, field -> index to field }
            .filter { (_, field) ->
                field.kind == OpenVpnProfileCodec.EditableKind.DIRECTIVE ||
                    field.kind == OpenVpnProfileCodec.EditableKind.INLINE_BLOCK &&
                    !field.name.substringBefore(' ').equals("auth-user-pass", ignoreCase = true)
            }
        val genericDirective = stringResource(R.string.openvpn_generic_directive)

        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.openvpn_editor_title),
                    onBackClick = { finish() },
                    actions = {
                        IconButton(onClick = { profileFilePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_import_profile_24dp),
                                contentDescription = stringResource(R.string.action_import_profile),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        if (editGuid.isNotBlank() && MmkvManager.getSelectServer() != editGuid) {
                            IconButton(onClick = { showDelete = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete_24dp),
                                    contentDescription = stringResource(R.string.acc_delete),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        IconButton(
                            enabled = credentialsLoaded,
                            onClick = {
                                saveProfile(
                                    remarks,
                                    rawConfig,
                                    OpenVpnCredentials(
                                        username = username,
                                        password = password,
                                        privateKeyPassphrase = privateKeyPassphrase,
                                        externalPkiAlias = externalPkiAlias,
                                        disableClientCertificate = disableClientCertificate,
                                        clientCertificatePem = clientCertificatePem,
                                        privateKeyPem = privateKeyPem,
                                    ),
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FormTextField(
                    label = stringResource(R.string.server_lab_remarks),
                    value = remarks,
                    onValueChange = { remarks = it },
                    maxLines = 1,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.openvpn_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.openvpn_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = privateKeyPassphrase,
                    onValueChange = { privateKeyPassphrase = it },
                    label = { Text(stringResource(R.string.openvpn_private_key_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                TextButton(
                    onClick = { certificateFilePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.openvpn_choose_certificate))
                }
                OutlinedTextField(
                    value = clientCertificatePem,
                    onValueChange = { clientCertificatePem = it },
                    label = { Text(stringResource(R.string.openvpn_certificate_pem)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = privateKeyPem,
                    onValueChange = { privateKeyPem = it },
                    label = { Text(stringResource(R.string.openvpn_private_key_pem)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                fields.forEach { (index, field) ->
                    Column {
                        Text(
                            text = when (field.kind) {
                                OpenVpnProfileCodec.EditableKind.DIRECTIVE -> field.name.takeIf(String::isNotBlank)
                                    ?.let { stringResource(R.string.openvpn_directive_field, it) }
                                    ?: stringResource(R.string.openvpn_new_directive_field)
                                OpenVpnProfileCodec.EditableKind.INLINE_BLOCK ->
                                    if (field.name.isBlank()) stringResource(R.string.openvpn_add_block)
                                    else stringResource(R.string.openvpn_block_field, field.name)
                                else -> ""
                            },
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        when (field.kind) {
                                OpenVpnProfileCodec.EditableKind.DIRECTIVE -> {
                                    if (field.name.equals("remote", ignoreCase = true)) {
                                        val endpoint = OpenVpnProfileCodec.remoteEndpoint(field.value)
                                        FormTextField(
                                            label = stringResource(R.string.openvpn_server_address),
                                            value = endpoint.address,
                                            onValueChange = { address ->
                                                rawConfig = OpenVpnProfileCodec.updateEditableRemote(
                                                    rawConfig,
                                                    index,
                                                    address,
                                                    endpoint.port,
                                                )
                                            },
                                            maxLines = 1,
                                        )
                                        FormTextField(
                                            label = stringResource(R.string.openvpn_server_port),
                                            value = endpoint.port,
                                            onValueChange = { port ->
                                                rawConfig = OpenVpnProfileCodec.updateEditableRemote(
                                                    rawConfig,
                                                    index,
                                                    endpoint.address,
                                                    port,
                                                )
                                            },
                                            maxLines = 1,
                                        )
                                    } else {
                                        if (field.name.isBlank()) FormTextField(
                                            label = stringResource(R.string.openvpn_option_name),
                                            value = field.name,
                                            onValueChange = { value ->
                                                rawConfig = OpenVpnProfileCodec.updateEditableField(
                                                    rawConfig, index, field.copy(name = value),
                                                )
                                            },
                                            maxLines = 1,
                                        )
                                        if (field.value.isNotBlank() ||
                                            !OpenVpnProfileCodec.isValuelessDirective(field.name)
                                        ) FormTextField(
                                            label = stringResource(
                                                R.string.openvpn_option_value,
                                                field.name.ifBlank { genericDirective },
                                            ),
                                            value = field.value,
                                            onValueChange = { value ->
                                                rawConfig = OpenVpnProfileCodec.updateEditableField(
                                                    rawConfig,
                                                    index,
                                                    field.copy(value = value),
                                                )
                                            },
                                            maxLines = 2,
                                        )
                                    }
                                }
                                OpenVpnProfileCodec.EditableKind.INLINE_BLOCK -> {
                                    if (field.name.isBlank()) FormTextField(
                                        label = stringResource(R.string.openvpn_block_name),
                                        value = field.name,
                                        onValueChange = { value ->
                                            rawConfig = OpenVpnProfileCodec.updateEditableField(
                                                rawConfig,
                                                index,
                                                field.copy(name = value),
                                            )
                                        },
                                        maxLines = 1,
                                    )
                                    if (field.name.isNotBlank()) FormTextField(
                                        label = stringResource(
                                            R.string.openvpn_block_contents,
                                            field.name,
                                        ),
                                        value = field.value,
                                        onValueChange = { value ->
                                            rawConfig = OpenVpnProfileCodec.updateEditableField(
                                                rawConfig,
                                                index,
                                                field.copy(value = value),
                                            )
                                        },
                                        maxLines = 8,
                                    )
                                }
                                OpenVpnProfileCodec.EditableKind.COMMENT,
                                OpenVpnProfileCodec.EditableKind.RAW,
                                OpenVpnProfileCodec.EditableKind.BLANK -> Unit
                        }
                    }
                }
                TextButton(
                    onClick = {
                        rawConfig = OpenVpnProfileCodec.addEditableField(
                            rawConfig,
                            OpenVpnProfileCodec.EditableKind.DIRECTIVE,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.openvpn_add_option))
                }
                TextButton(
                    onClick = {
                        rawConfig = OpenVpnProfileCodec.addEditableField(
                            rawConfig,
                            OpenVpnProfileCodec.EditableKind.INLINE_BLOCK,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.openvpn_add_block))
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
    }

    private fun readProfileText(uri: Uri): String =
        readBoundedText(uri, 512 * 1024, R.string.openvpn_import_invalid)

    private fun readBoundedText(uri: Uri, maxCharacters: Int, errorMessageRes: Int): String {
        val input = contentResolver.openInputStream(uri)
            ?: error(getString(errorMessageRes))
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8_192)
            while (true) {
                val remaining = maxCharacters + 1 - text.length
                if (remaining <= 0) error(getString(errorMessageRes))
                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                text.append(buffer, 0, count)
            }
            require(text.length <= maxCharacters) { getString(errorMessageRes) }
            text.toString()
        }
    }

    private fun deleteProfile() {
        if (editGuid.isBlank() || MmkvManager.getSelectServer() == editGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                OpenVpnCredentialStore.delete(applicationContext, editGuid)
            }
            MmkvManager.removeServer(editGuid)
            ProfileEditorResult.run { finishDeleted(editGuid) }
        }
    }

    private fun saveProfile(remarks: String, rawConfig: String, credentials: OpenVpnCredentials) {
        if (remarks.isBlank()) {
            toast(R.string.server_lab_remarks)
            return
        }
        val savedConfig = OpenVpnProfileCodec.removeEmptyDirectivePlaceholders(
            OpenVpnProfileCodec.normalizeCredentialFileDirectives(rawConfig),
        )
        if (savedConfig.isBlank()) {
            toast(R.string.openvpn_import_invalid)
            return
        }

        val fields = OpenVpnProfileCodec.detect(savedConfig)
        val current = MmkvManager.decodeServerConfig(editGuid) ?: initialProfile
        val updated = current.copy(
            configType = EConfigType.OPENVPN,
            remarks = remarks.trim(),
            server = fields.remote,
            serverPort = fields.port,
            network = fields.protocol,
            openVpnConfig = savedConfig,
            description = AngConfigManager.generateDescription(current.copy(server = fields.remote, serverPort = fields.port)),
            subscriptionId = current.subscriptionId.ifBlank { intent.getStringExtra(EXTRA_SUBSCRIPTION_ID).orEmpty() },
        )
        if (editGuid.isNotBlank() && current.subscriptionId.isNotBlank()) {
            SubscriptionProfileOverrides.save(editGuid, initialProfile, updated)
        }
        val savedGuid = MmkvManager.encodeServerConfig(editGuid, updated)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    OpenVpnCredentialStore.save(applicationContext, savedGuid, credentials)
                }
            }
            if (result.isFailure) {
                toast(R.string.toast_failure)
                return@launch
            }
            toastSuccess(R.string.toast_success)
            ProfileEditorResult.run {
                finishSaved(guid = savedGuid, restartService = false)
            }
        }
    }

    companion object {
        const val EXTRA_GUID = "guid"
        const val EXTRA_CONFIG_TEXT = "openvpn.config"
        const val EXTRA_FILE_NAME = "openvpn.file_name"
        const val EXTRA_SUBSCRIPTION_ID = "openvpn.subscription_id"
    }
}

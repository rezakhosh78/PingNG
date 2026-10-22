package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.OpenVpnProfileCodec
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionProfileOverrides
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.server.ProfileEditorResult

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
        var remarks by rememberSaveable(editGuid) { mutableStateOf(initialProfile.remarks) }
        var rawConfig by rememberSaveable(editGuid) {
            mutableStateOf(initialProfile.openVpnConfig.orEmpty())
        }
        val fields = OpenVpnProfileCodec.editableFields(rawConfig)
            .mapIndexed { index, field -> index to field }
            .filter { (_, field) ->
                field.kind == OpenVpnProfileCodec.EditableKind.DIRECTIVE ||
                    field.kind == OpenVpnProfileCodec.EditableKind.INLINE_BLOCK
            }
        val genericDirective = stringResource(R.string.openvpn_generic_directive)

        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.openvpn_editor_title),
                    onBackClick = { finish() },
                    actions = {
                        IconButton(onClick = { saveProfile(remarks, rawConfig) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fab_check),
                                contentDescription = stringResource(R.string.acc_save),
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
                Text(
                    text = stringResource(R.string.openvpn_config_edit_note),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormTextField(
                    label = stringResource(R.string.server_lab_remarks),
                    value = remarks,
                    onValueChange = { remarks = it },
                    maxLines = 1,
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
    }

    private fun saveProfile(remarks: String, rawConfig: String) {
        if (remarks.isBlank()) {
            toast(R.string.server_lab_remarks)
            return
        }
        val savedConfig = OpenVpnProfileCodec.removeEmptyDirectivePlaceholders(rawConfig)
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
        toastSuccess(R.string.toast_success)
        ProfileEditorResult.run {
            finishSaved(guid = savedGuid, restartService = false)
        }
    }

    companion object {
        const val EXTRA_GUID = "guid"
        const val EXTRA_CONFIG_TEXT = "openvpn.config"
        const val EXTRA_FILE_NAME = "openvpn.file_name"
        const val EXTRA_SUBSCRIPTION_ID = "openvpn.subscription_id"
    }
}

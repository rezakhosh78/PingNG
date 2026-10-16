package com.v2ray.ang.ui.server

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SmartDesyncArgumentsEditor
import com.v2ray.ang.ui.main.DesyncSearchDialog

class ServerVlessActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.VLESS

    @Composable
    override fun ScreenContent() {
        val options = rememberFieldOptions()
        val scope = rememberCoroutineScope()
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(
                initialConfig = initialConfig
            )
        }.apply {
            configType = EConfigType.VLESS
        }
        val flowOptions = stringArrayResource(R.array.flows).toList()
        val pingNgProfiles = stringArrayResource(R.array.pingng_profiles).toList()
        var showDesyncSearch by rememberSaveable { mutableStateOf(false) }
        var desyncSearchGuid by rememberSaveable { mutableStateOf(editGuid) }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)
            VlessProtocolFields(uiState, flowOptions)
            CommonNetworkFields(uiState, options)
            CommonStreamSecurityFields(
                state = uiState,
                options = options,
                scope = scope,
                buildProfileItem = { uiState.toProfileItem(initialConfig) }
            )
            FormDropdownField(
                stringResource(R.string.pingng_compat_profile),
                uiState.pingNgProfile,
                pingNgProfiles,
                {
                    uiState.pingNgProfile = it
                    if (it == "Custom" && uiState.pingNgDesyncArgs.isBlank()) {
                        uiState.pingNgDesyncArgs = com.v2ray.ang.core.PingNgCompat.buildCustomArguments(
                            com.v2ray.ang.core.PingNgCompat.CustomOptions()
                        )
                    }
                }
            )
            Button(
                onClick = {
                    saveDraftForDesyncSearch(uiState)?.let { guid ->
                        desyncSearchGuid = guid
                        showDesyncSearch = true
                    }
                },
                modifier = Modifier.padding(start = 16.dp),
            ) {
                Text(stringResource(R.string.pingng_find_desync_setting))
            }
            if (uiState.pingNgProfile == "Custom") {
                SmartDesyncArgumentsEditor(
                    arguments = uiState.pingNgDesyncArgs,
                    onArgumentsChange = { uiState.pingNgDesyncArgs = it },
                    onReset = {
                        uiState.pingNgProfile = "Off"
                        uiState.pingNgDesyncArgs = ""
                    },
                )
            }
            PsiphonFields(uiState)
        }

        if (showDesyncSearch) {
            DesyncSearchDialog(
                profiles = listOf(desyncSearchGuid to uiState.toProfileItem(initialConfig)),
                fixedProfileGuid = desyncSearchGuid,
                onStartSearch = { guid, profile, family, advanced, limit, workers, progress, report, finished ->
                    runDesyncSearch(
                        guid = guid,
                        profile = profile,
                        family = family,
                        advanced = advanced,
                        maxProfiles = limit,
                        workers = workers,
                        progress = progress,
                        report = report,
                        finished = finished,
                    )
                },
                onCancelSearch = ::cancelDesyncSearch,
                onApply = { _, _, args ->
                    uiState.pingNgProfile = "Custom"
                    uiState.pingNgDesyncArgs = args
                    applyDesyncResult(desyncSearchGuid, args)
                },
                onDismiss = { showDesyncSearch = false },
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.password.isNullOrBlank()) {
            toast(R.string.server_lab_id)
            return false
        }
        return true
    }

    @Composable
    private fun VlessProtocolFields(
        state: ServerUiState,
        flowOptions: List<String>
    ) {
        FormTextField(
            stringResource(R.string.server_lab_id),
            state.password,
            { state.password = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_encryption),
            state.encryption,
            { state.encryption = it }
        )
        FormDropdownField(
            stringResource(R.string.server_lab_flow),
            state.flow,
            flowOptions,
            { state.flow = it }
        )
    }
}

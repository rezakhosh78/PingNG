package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import java.util.UUID

/** The embedded Psiphon runtime is the profile's own network tunnel. */
class ServerPsiphonActivity : BaseServerActivity() {
    override val serverConfigType = EConfigType.SOCKS

    @Composable
    override fun ScreenContent() {
        var remark by rememberSaveable(editGuid) {
            mutableStateOf(initialConfig.remarks.ifBlank { "Psiphon" })
        }
        var country by rememberSaveable(editGuid) {
            mutableStateOf(initialConfig.psiphonRegion.orEmpty().ifBlank { "ANY" })
        }
        Scaffold(topBar = {
            AppTopBar(
                title = stringResource(R.string.psiphon_editor_title),
                onBackClick = { finish() },
                actions = {
                    IconButton(onClick = {
                        val guid = editGuid.ifBlank { UUID.randomUUID().toString() }
                        val saved = MmkvManager.encodeServerConfig(guid, initialConfig.copy(
                            configType = EConfigType.SOCKS,
                            subscriptionId = initialConfig.subscriptionId.ifBlank { subscriptionId.orEmpty() },
                            remarks = remark.trim().ifBlank { "Psiphon" },
                            description = "Psiphon",
                            server = "127.0.0.1",
                            serverPort = "1",
                            psiphonStandalone = true,
                            psiphonEnabled = true,
                            psiphonRegion = country,
                            psiphonMode = "auto",
                            torEnabled = false,
                        ))
                        toastSuccess(R.string.toast_success)
                        ProfileEditorResult.run { finishSaved(saved, restartService = isRunning) }
                    }) {
                        Icon(painterResource(R.drawable.ic_fab_check), contentDescription = stringResource(R.string.acc_save))
                    }
                },
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                FormTextField("Remark", remark, { remark = it }, maxLines = 1)
                FormDropdownField(
                    label = stringResource(R.string.psiphon_editor_country),
                    value = PsiphonRegions.displayName(country),
                    options = PsiphonRegions.displayOptions(),
                    onValueChange = { country = PsiphonRegions.codeOf(it) },
                )
            }
        }
    }
}

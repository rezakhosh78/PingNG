package com.v2ray.ang.ui.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R

@Composable
fun OpenVpnCertificateChoiceDialog(
    onContinueWithoutCertificate: () -> Unit,
    onSelectCertificate: () -> Unit,
    onImportPemIdentity: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.openvpn_certificate_prompt_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.openvpn_certificate_prompt_message))
                OutlinedButton(onClick = onImportPemIdentity) {
                    Text(stringResource(R.string.openvpn_import_pem_identity))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onContinueWithoutCertificate) {
                Text(stringResource(R.string.openvpn_continue_without_certificate))
            }
        },
        confirmButton = {
            TextButton(onClick = onSelectCertificate) {
                Text(stringResource(R.string.openvpn_select_certificate))
            }
        },
    )
}

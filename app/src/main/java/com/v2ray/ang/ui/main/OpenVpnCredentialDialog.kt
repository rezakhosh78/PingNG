package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.fmt.OpenVpnProfileCodec
import com.v2ray.ang.handler.OpenVpnCredentials

@Composable
fun OpenVpnCredentialDialog(
    profileGuid: String,
    requirements: OpenVpnProfileCodec.CredentialRequirements,
    initialCredentials: OpenVpnCredentials,
    onConnect: (OpenVpnCredentials) -> Unit,
    onChooseCertificate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember(profileGuid) { mutableStateOf(initialCredentials.username) }
    var password by remember(profileGuid) { mutableStateOf(initialCredentials.password) }
    var privateKeyPassphrase by remember(profileGuid) {
        mutableStateOf(initialCredentials.privateKeyPassphrase)
    }
    var externalPkiAlias by remember(profileGuid) {
        mutableStateOf(initialCredentials.externalPkiAlias)
    }
    var clientCertificatePem by remember(profileGuid) {
        mutableStateOf(initialCredentials.clientCertificatePem)
    }
    var privateKeyPem by remember(profileGuid) {
        mutableStateOf(initialCredentials.privateKeyPem)
    }
    var disableClientCertificate by remember(profileGuid) {
        mutableStateOf(initialCredentials.disableClientCertificate)
    }
    LaunchedEffect(
        initialCredentials.username,
        initialCredentials.password,
        initialCredentials.privateKeyPassphrase,
    ) {
        username = initialCredentials.username
        password = initialCredentials.password
        privateKeyPassphrase = initialCredentials.privateKeyPassphrase
    }
    LaunchedEffect(
        initialCredentials.externalPkiAlias,
        initialCredentials.disableClientCertificate,
        initialCredentials.clientCertificatePem,
        initialCredentials.privateKeyPem,
    ) {
        externalPkiAlias = initialCredentials.externalPkiAlias
        disableClientCertificate = initialCredentials.disableClientCertificate
        clientCertificatePem = initialCredentials.clientCertificatePem
        privateKeyPem = initialCredentials.privateKeyPem
    }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.openvpn_credentials_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(scrollState)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.openvpn_credentials_saved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.openvpn_credentials_type_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (requirements.usernamePassword) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.openvpn_username)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.openvpn_password)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (requirements.privateKeyPassphrase || requirements.externalPki) {
                    OutlinedTextField(
                        value = privateKeyPassphrase,
                        onValueChange = { privateKeyPassphrase = it },
                        label = { Text(stringResource(R.string.openvpn_private_key_passphrase)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (requirements.externalPki) {
                    Text(
                        text = stringResource(R.string.openvpn_client_certificate),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = if (externalPkiAlias.isBlank()) {
                            if (clientCertificatePem.isNotBlank()) {
                                stringResource(R.string.openvpn_certificate_loaded)
                            } else stringResource(R.string.openvpn_choose_certificate)
                        } else {
                            stringResource(R.string.openvpn_selected_certificate, externalPkiAlias)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onChooseCertificate) {
                        Text(stringResource(R.string.openvpn_choose_certificate))
                    }
                    OutlinedTextField(
                        value = clientCertificatePem,
                        onValueChange = { clientCertificatePem = it },
                        label = { Text(stringResource(R.string.openvpn_certificate_pem)) },
                        minLines = 3,
                        maxLines = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = privateKeyPem,
                        onValueChange = { privateKeyPem = it },
                        label = { Text(stringResource(R.string.openvpn_private_key_pem)) },
                        minLines = 3,
                        maxLines = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = disableClientCertificate,
                                role = Role.Checkbox,
                                onValueChange = { disableClientCertificate = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = disableClientCertificate,
                            onCheckedChange = null,
                        )
                        Text(stringResource(R.string.openvpn_no_certificate))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConnect(
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
                Text(stringResource(R.string.openvpn_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

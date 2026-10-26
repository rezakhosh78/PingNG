package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.BepassSettings
import com.v2ray.ang.ui.compose.FormTextField

@Composable
fun BepassFields(state: ServerUiState) {
    if (!BepassSettings.isSupported(state.configType, state.network, state.streamSecurity)) return

    val settingsState = remember(state) {
        mutableStateOf(BepassSettings.fromJson(state.bepassSettingsJson))
    }
    val settings = settingsState.value
    val update: ((BepassSettings) -> BepassSettings) -> Unit = { transform ->
        val next = transform(settingsState.value)
        settingsState.value = next
        state.bepassSettingsJson = next.toJson()
    }
    var expanded by rememberSaveable { mutableStateOf(state.bepassEnabled) }
    val expandDescription = stringResource(
        if (expanded) R.string.bepass_collapse_settings else R.string.bepass_expand_settings,
    )
    val toggleDescription = stringResource(R.string.bepass_toggle_label)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bepass_label), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.bepass_short_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.bepassEnabled) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics { contentDescription = expandDescription },
                ) {
                    Text(
                        text = if (expanded) "⌃" else "⌄",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = state.bepassEnabled,
                onCheckedChange = {
                    state.bepassEnabled = it
                    expanded = it
                },
                modifier = Modifier
                    .scale(0.8f)
                    .semantics { contentDescription = toggleDescription },
            )
        }

        if (state.bepassEnabled && expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsToggle(
                    title = stringResource(R.string.bepass_tls_padding_enabled),
                    checked = settings.tlsPaddingEnabled,
                    onCheckedChange = { update { current -> current.copy(tlsPaddingEnabled = it) } },
                )
                numberField(R.string.bepass_tls_header_length, settings.tlsHeaderLength, onValue = {
                    update { current -> current.copy(tlsHeaderLength = it) }
                })
                if (settings.tlsPaddingEnabled) {
                    numberField(R.string.bepass_tls_padding_min, settings.tlsPaddingMin, onValue = {
                        update { current -> current.copy(tlsPaddingMin = it) }
                    })
                    numberField(R.string.bepass_tls_padding_max, settings.tlsPaddingMax, onValue = {
                        update { current -> current.copy(tlsPaddingMax = it) }
                    })
                }
                FormTextField(
                    stringResource(R.string.bepass_remote_dns_address), settings.remoteDnsAddress,
                    { update { current -> current.copy(remoteDnsAddress = it) } },
                )
                SettingsToggle(
                    title = stringResource(R.string.bepass_dns_fragmentation_enabled),
                    checked = settings.dnsFragmentationEnabled,
                    onCheckedChange = { update { current -> current.copy(dnsFragmentationEnabled = it) } },
                )
                numberField(R.string.bepass_dns_cache_ttl, settings.dnsCacheTtlSeconds, onValue = {
                    update { current -> current.copy(dnsCacheTtlSeconds = it) }
                })
                numberField(R.string.bepass_dns_request_timeout, settings.dnsRequestTimeoutSeconds, onValue = {
                    update { current -> current.copy(dnsRequestTimeoutSeconds = it) }
                })

                numberField(R.string.bepass_chunks_before_sni_min, settings.chunksBeforeSniMin, onValue = {
                    update { current -> current.copy(chunksBeforeSniMin = it) }
                })
                numberField(R.string.bepass_chunks_before_sni_max, settings.chunksBeforeSniMax, onValue = {
                    update { current -> current.copy(chunksBeforeSniMax = it) }
                })
                numberField(R.string.bepass_sni_chunk_min, settings.sniChunkMin, onValue = {
                    update { current -> current.copy(sniChunkMin = it) }
                })
                numberField(R.string.bepass_sni_chunk_max, settings.sniChunkMax, onValue = {
                    update { current -> current.copy(sniChunkMax = it) }
                })
                numberField(R.string.bepass_chunks_after_sni_min, settings.chunksAfterSniMin, onValue = {
                    update { current -> current.copy(chunksAfterSniMin = it) }
                })
                numberField(R.string.bepass_chunks_after_sni_max, settings.chunksAfterSniMax, onValue = {
                    update { current -> current.copy(chunksAfterSniMax = it) }
                })
                numberField(R.string.bepass_delay_between_chunks_min, settings.delayBetweenChunksMin, onValue = {
                    update { current -> current.copy(delayBetweenChunksMin = it) }
                })
                numberField(R.string.bepass_delay_between_chunks_max, settings.delayBetweenChunksMax, onValue = {
                    update { current -> current.copy(delayBetweenChunksMax = it) }
                })

                SettingsToggle(
                    title = stringResource(R.string.bepass_worker_enabled),
                    checked = settings.workerEnabled,
                    onCheckedChange = { update { current -> current.copy(workerEnabled = it) } },
                )
                if (settings.workerEnabled) {
                    FormTextField(
                        stringResource(R.string.bepass_worker_address), settings.workerAddress,
                        { update { current -> current.copy(workerAddress = it) } },
                    )
                    FormTextField(
                        stringResource(R.string.bepass_worker_ip_port_address), settings.workerIpPortAddress,
                        { update { current -> current.copy(workerIpPortAddress = it) } },
                    )
                    SettingsToggle(
                        title = stringResource(R.string.bepass_worker_dns_only),
                        checked = settings.workerDnsOnly,
                        onCheckedChange = { update { current -> current.copy(workerDnsOnly = it) } },
                    )
                }
                Text(
                    stringResource(R.string.bepass_low_level_sockets_note),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormTextField(
                    stringResource(R.string.bepass_hosts), settings.hosts,
                    { update { current -> current.copy(hosts = it) } },
                    maxLines = 3,
                )
                Text(
                    stringResource(R.string.bepass_udp_advanced_note),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                numberField(R.string.bepass_udp_read_timeout, settings.udpReadTimeoutSeconds, onValue = {
                    update { current -> current.copy(udpReadTimeoutSeconds = it) }
                })
                numberField(R.string.bepass_udp_write_timeout, settings.udpWriteTimeoutSeconds, onValue = {
                    update { current -> current.copy(udpWriteTimeoutSeconds = it) }
                })
                numberField(R.string.bepass_udp_link_idle_timeout, settings.udpLinkIdleTimeoutSeconds.toInt(), onValue = {
                    update { current -> current.copy(udpLinkIdleTimeoutSeconds = it.toLong()) }
                })
            }
        }
    }
}

@Composable
private fun numberField(labelRes: Int, value: Int, onValue: (Int) -> Unit) {
    var draft by rememberSaveable(labelRes, value) { mutableStateOf(value.toString()) }
    FormTextField(
        label = stringResource(labelRes),
        value = draft,
        onValueChange = { next ->
            draft = next
            next.toIntOrNull()?.let(onValue)
        },
        keyboardType = KeyboardType.Number,
        maxLines = 1,
        onFocusLost = { draft = value.toString() },
    )
}

@Composable
private fun SettingsToggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .scale(0.8f)
                .semantics { contentDescription = title },
        )
    }
}

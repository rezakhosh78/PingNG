package com.v2ray.ang.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.PingNgCompat

@Composable
fun SmartDesyncArgumentsEditor(
    arguments: String,
    onArgumentsChange: (String) -> Unit,
    onReset: (() -> Unit)? = null,
) {
    var options by remember {
        mutableStateOf(PingNgCompat.parseCustomOptions(arguments))
    }
    var fakeSniFieldValue by remember { mutableStateOf(TextFieldValue(options.fakeSni)) }
    var lastEmittedArguments by remember { mutableStateOf(arguments) }
    var advancedExpanded by remember { mutableStateOf(false) }

    // Parent state changes on every keystroke. Only reload from the parent when
    // the change did not originate from this editor, otherwise deleting the
    // last digit would immediately re-create the default value.
    LaunchedEffect(arguments) {
        if (arguments != lastEmittedArguments) {
            options = PingNgCompat.parseCustomOptions(arguments)
            fakeSniFieldValue = TextFieldValue(options.fakeSni)
            lastEmittedArguments = arguments
        }
    }

    fun update(value: PingNgCompat.CustomOptions) {
        options = value
        val encoded = PingNgCompat.buildCustomArguments(value)
        lastEmittedArguments = encoded
        onArgumentsChange(encoded)
    }

    FormDropdownField(
        label = stringResource(R.string.pingng_custom_method),
        value = options.method,
        options = listOf(
            PingNgCompat.METHOD_SPLIT,
            PingNgCompat.METHOD_DISORDER,
            PingNgCompat.METHOD_FAKE_SNI,
            PingNgCompat.METHOD_OUT_OF_BAND,
            PingNgCompat.METHOD_DISORDER_OUT_OF_BAND,
        ),
        onValueChange = { method ->
            val oobTtl = when (method) {
                PingNgCompat.METHOD_OUT_OF_BAND,
                PingNgCompat.METHOD_DISORDER_OUT_OF_BAND -> PingNgCompat.defaultOobTtl(method)
                else -> options.oobTtl
            }
            update(options.copy(method = method, oobTtl = oobTtl))
        },
        supportingText = stringResource(R.string.pingng_method_hint),
    )

    FormTextField(
        label = stringResource(R.string.pingng_custom_position),
        value = options.position,
        onValueChange = { update(options.copy(position = it.filter { char -> char.isDigit() || char == '-' })) },
        keyboardType = KeyboardType.Number,
    )

    if (options.method == PingNgCompat.METHOD_FAKE_SNI) {
        FormTextField(
            label = stringResource(R.string.pingng_custom_ttl),
            value = options.fakeTtl,
            onValueChange = { update(options.copy(fakeTtl = it.filter { char -> char.isDigit() })) },
            keyboardType = KeyboardType.Number,
        )
        FormTextFieldValue(
            label = stringResource(R.string.pingng_custom_fake_sni),
            value = fakeSniFieldValue,
            onValueChange = { typedValue ->
                // Preserve Compose's selection. Replacing a String value while
                // the user types a dot or comma moves the cursor and reverses
                // the next token; TextFieldValue avoids that reconstruction.
                val normalized = PingNgCompat.normalizeFakeSniTyping(typedValue.text)
                fakeSniFieldValue = if (normalized == typedValue.text) {
                    typedValue
                } else {
                    TextFieldValue(normalized, TextRange(normalized.length))
                }
            },
            maxLines = 1,
            textDirection = TextDirection.Ltr,
            onFocusLost = {
                val canonical = PingNgCompat.canonicalFakeSniList(fakeSniFieldValue.text)
                fakeSniFieldValue = TextFieldValue(canonical)
                update(options.copy(fakeSni = canonical))
            },
        )
    }

    if (
        options.method == PingNgCompat.METHOD_OUT_OF_BAND ||
        options.method == PingNgCompat.METHOD_DISORDER_OUT_OF_BAND
    ) {
        FormTextField(
            label = stringResource(R.string.pingng_custom_oob_ttl),
            value = options.oobTtl,
            onValueChange = { update(options.copy(oobTtl = it.filter(Char::isDigit))) },
            keyboardType = KeyboardType.Number,
            onFocusLost = {
                update(options.copy(oobTtl = PingNgCompat.normalizeOobTtl(options.method, options.oobTtl)))
            },
        )
    }

    FormTextField(
        label = stringResource(R.string.pingng_custom_tls_record),
        value = options.tlsRecordPosition,
        onValueChange = { update(options.copy(tlsRecordPosition = it.filter { char ->
            char.isDigit() || char == ',' || char == ';' || char == ' ' || char == '|' || char == '-'
        })) },
        keyboardType = KeyboardType.Number,
        placeholder = stringResource(R.string.pingng_tls_record_hint),
    )

    FormTextField(
        label = stringResource(R.string.pingng_custom_timeout),
        value = options.timeoutSeconds,
        onValueChange = { update(options.copy(timeoutSeconds = it.filter(Char::isDigit))) },
        keyboardType = KeyboardType.Number,
    )

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { advancedExpanded = !advancedExpanded }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.pingng_advanced_settings),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
        Text(if (advancedExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.secondary)
    }

    if (advancedExpanded) {
        FormTextField(
            label = stringResource(R.string.pingng_cache_ttl),
            value = options.cacheTtlSeconds,
            onValueChange = { update(options.copy(cacheTtlSeconds = it.filter(Char::isDigit))) },
            keyboardType = KeyboardType.Number,
        )
        FormTextField(
            label = stringResource(R.string.pingng_delay_range),
            value = options.delayRange,
            onValueChange = { update(options.copy(delayRange = it.filter { char -> char.isDigit() || char == '-' })) },
            keyboardType = KeyboardType.Number,
        )
        if (options.method == PingNgCompat.METHOD_SPLIT) {
            FormTextField(
                label = stringResource(R.string.pingng_split_range),
                value = options.splitRange,
                onValueChange = { update(options.copy(splitRange = it.filter { char -> char.isDigit() || char == '-' })) },
                keyboardType = KeyboardType.Number,
                placeholder = stringResource(R.string.pingng_range_hint),
            )
        }
        if (options.method == PingNgCompat.METHOD_FAKE_SNI) {
            FormTextField(
                label = stringResource(R.string.pingng_ttl_range),
                value = options.ttlRange,
                onValueChange = { update(options.copy(ttlRange = it.filter { char -> char.isDigit() || char == '-' })) },
                keyboardType = KeyboardType.Number,
                placeholder = stringResource(R.string.pingng_range_hint),
            )
        }
        FormTextField(
            label = stringResource(R.string.pingng_udp_fake_count),
            value = options.udpFakeCount,
            onValueChange = { update(options.copy(udpFakeCount = it.filter(Char::isDigit))) },
            keyboardType = KeyboardType.Number,
        )
        if (options.method == PingNgCompat.METHOD_FAKE_SNI) {
            FormTextField(
                label = stringResource(R.string.pingng_fake_offset),
                value = options.fakeOffset,
                onValueChange = { update(options.copy(fakeOffset = it.filter(Char::isDigit))) },
                keyboardType = KeyboardType.Number,
            )
            DesyncSwitchRow(
                title = stringResource(R.string.pingng_fake_jitter),
                checked = options.fakeJitter,
                onCheckedChange = { update(options.copy(fakeJitter = it)) },
            )
        }
        if (options.method == PingNgCompat.METHOD_FAKE_SNI) {
            FormTextField(
                label = stringResource(R.string.pingng_fake_data),
                value = options.fakeData,
                onValueChange = { update(options.copy(fakeData = it)) },
                placeholder = stringResource(R.string.pingng_fake_data_hint),
            )
        }
        if (
            options.method == PingNgCompat.METHOD_OUT_OF_BAND ||
            options.method == PingNgCompat.METHOD_DISORDER_OUT_OF_BAND
        ) {
            FormTextField(
                label = stringResource(R.string.pingng_oob_data),
                value = options.oobData,
                onValueChange = { update(options.copy(oobData = it)) },
                placeholder = stringResource(R.string.pingng_oob_data_hint),
                maxLines = 1,
            )
        }
        FormTextField(
            label = stringResource(R.string.pingng_hosts),
            value = options.hosts,
            onValueChange = { update(options.copy(hosts = it)) },
            placeholder = stringResource(R.string.pingng_hosts_hint),
        )
        FormTextField(
            label = stringResource(R.string.pingng_port_filter),
            value = options.portFilter,
            onValueChange = { update(options.copy(portFilter = it.filter { char -> char.isDigit() || char == '-' })) },
            keyboardType = KeyboardType.Number,
            placeholder = "443 or 80-443",
        )
        DesyncSwitchRow(
            title = stringResource(R.string.pingng_auto_fallback),
            checked = options.automaticFallback,
            onCheckedChange = { update(options.copy(automaticFallback = it)) },
        )
        DesyncSwitchRow(
            title = stringResource(R.string.pingng_http_modifiers),
            checked = options.modifyHttpHeaders,
            onCheckedChange = { update(options.copy(modifyHttpHeaders = it)) },
        )
        DesyncSwitchRow(
            title = stringResource(R.string.pingng_wait_for_send),
            checked = options.waitForSend,
            onCheckedChange = { update(options.copy(waitForSend = it)) },
        )
        DesyncSwitchRow(
            title = stringResource(R.string.pingng_tfo),
            checked = options.tcpFastOpen,
            onCheckedChange = { update(options.copy(tcpFastOpen = it)) },
        )
        DesyncSwitchRow(
            title = stringResource(R.string.pingng_drop_sack),
            checked = options.dropSack,
            onCheckedChange = { update(options.copy(dropSack = it)) },
        )
        Text(
            text = stringResource(R.string.pingng_advanced_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    if (onReset != null) {
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(stringResource(R.string.pingng_desync_reset))
        }
    }
}

@Composable
private fun DesyncSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

package com.v2ray.ang.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.v2ray.ang.R
import com.v2ray.ang.core.PingNgCompat

@Composable
fun SmartDesyncArgumentsEditor(
    arguments: String,
    onArgumentsChange: (String) -> Unit,
) {
    var options by remember { mutableStateOf(PingNgCompat.parseCustomOptions(arguments)) }

    fun update(value: PingNgCompat.CustomOptions) {
        options = value
        onArgumentsChange(PingNgCompat.buildCustomArguments(value))
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
        onValueChange = { update(options.copy(method = it)) },
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
        FormTextField(
            label = stringResource(R.string.pingng_custom_fake_sni),
            value = options.fakeSni,
            onValueChange = {
                update(options.copy(fakeSni = PingNgCompat.normalizeFakeSniList(it)))
            },
        )
    }
    
    FormTextField(
        label = stringResource(R.string.pingng_custom_tls_record),
        value = options.tlsRecordPosition,
        onValueChange = { update(options.copy(tlsRecordPosition = it.filter { char -> char.isDigit() })) },
        keyboardType = KeyboardType.Number,
    )
    
    FormTextField(
        label = stringResource(R.string.pingng_custom_timeout),
        value = options.timeoutSeconds,
        onValueChange = { update(options.copy(timeoutSeconds = it.filter { char -> char.isDigit() })) },
        keyboardType = KeyboardType.Number,
    )
}

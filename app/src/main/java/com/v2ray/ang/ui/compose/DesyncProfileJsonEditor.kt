package com.v2ray.ang.ui.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.DesyncProfileJson

/** Editable JSON view that synchronizes every valid edit with the profile and engine arguments. */
@Composable
fun DesyncProfileJsonEditor(
    profile: String,
    arguments: String,
    onChange: (profile: String, arguments: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var jsonText by remember { mutableStateOf(DesyncProfileJson.toJson(profile, arguments)) }
    var error by remember { mutableStateOf(false) }
    var pendingSelfUpdate by remember { mutableStateOf<DesyncProfileJson.Settings?>(null) }
    var lastObservedProfile by remember { mutableStateOf(profile) }
    var lastObservedArguments by remember { mutableStateOf(arguments) }

    LaunchedEffect(profile, arguments) {
        if (profile != lastObservedProfile || arguments != lastObservedArguments) {
            if (pendingSelfUpdate != DesyncProfileJson.Settings(profile, arguments)) {
                // Changes from the dropdown or structured editor flow back into JSON.
                jsonText = DesyncProfileJson.toJson(profile, arguments)
                error = false
            } else {
                // Keep the text and cursor in place for changes typed in this JSON field.
                pendingSelfUpdate = null
            }
            lastObservedProfile = profile
            lastObservedArguments = arguments
        }
    }

    fun applyValidJson(text: String): DesyncProfileJson.Settings? {
        val settings = DesyncProfileJson.fromJson(text, profile, arguments) ?: return null
        if (settings.profile != profile || settings.arguments != arguments) {
            pendingSelfUpdate = settings
            onChange(settings.profile, settings.arguments)
        }
        return settings
    }

    FormTextField(
        label = stringResource(R.string.pingng_desync_json),
        value = jsonText,
        onValueChange = { updatedText ->
            jsonText = updatedText
            error = false
            // JSON is applied as soon as it becomes valid; incomplete typing is kept local.
            applyValidJson(updatedText)
        },
        modifier = modifier,
        maxLines = 10,
        textDirection = TextDirection.Ltr,
        supportingText = stringResource(R.string.pingng_desync_json_hint),
        onFocusLost = {
            val settings = applyValidJson(jsonText)
            if (settings == null) {
                error = true
            } else {
                error = false
                jsonText = DesyncProfileJson.toJson(settings.profile, settings.arguments)
            }
        },
    )
    if (error) {
        Text(
            text = stringResource(R.string.pingng_desync_json_invalid),
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(jsonText)) },
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.pingng_desync_json_copy))
        }
    }
}

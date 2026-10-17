package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.SmartDesyncArgumentsEditor

@Composable
fun DesyncSettingsDialog(
    profile: ProfileItem,
    onSave: (desyncProfile: String, customArgs: String) -> Unit,
    onFindBestDesync: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProfile by remember(profile) {
        mutableStateOf(profile.pingNgProfile?.takeIf { it.isNotBlank() } ?: PingNgCompat.PROFILE_OFF)
    }
    var customArgs by remember(profile) { mutableStateOf(profile.pingNgDesyncArgs.orEmpty()) }
    val isSupported = PingNgCompat.supportsNativeDesync(profile)
    val customArgsMissing = selectedProfile == PingNgCompat.PROFILE_CUSTOM && customArgs.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pingng_desync_for_profile, profile.remarks)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FormDropdownField(
                    label = stringResource(R.string.pingng_compat_profile),
                    value = selectedProfile,
                    options = stringArrayResource(R.array.pingng_profiles).toList(),
                    supportingText = stringResource(profileHintRes(selectedProfile)),
                    onValueChange = {
                        selectedProfile = it
                        if (it == PingNgCompat.PROFILE_CUSTOM && customArgs.isBlank()) {
                            customArgs = PingNgCompat.buildCustomArguments(PingNgCompat.CustomOptions())
                        } else if (it != PingNgCompat.PROFILE_CUSTOM && it != PingNgCompat.PROFILE_OFF) {
                            customArgs = PingNgCompat.getPresetArguments(it)
                        }
                    },
                    enabled = isSupported,
                )
                if (!isSupported) {
                    Text(
                        text = stringResource(R.string.pingng_desync_unsupported),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (selectedProfile != PingNgCompat.PROFILE_OFF && isSupported) {
                    SmartDesyncArgumentsEditor(
                        arguments = customArgs,
                        onArgumentsChange = { 
                            customArgs = it 
                            selectedProfile = PingNgCompat.PROFILE_CUSTOM
                        },
                        onReset = {
                            selectedProfile = PingNgCompat.PROFILE_OFF
                            customArgs = ""
                        },
                    )
                    if (customArgsMissing) {
                        Text(
                            text = stringResource(R.string.pingng_custom_args_required),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = stringResource(R.string.pingng_custom_generated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Text(
                        text = customArgs,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    TextButton(
                        onClick = {
                            onFindBestDesync()
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.pingng_find_best_desync))
                    }
                }
                if (isSupported && selectedProfile == PingNgCompat.PROFILE_OFF) {
                    OutlinedButton(
                        onClick = {
                            onSave(PingNgCompat.PROFILE_OFF, "")
                            onDismiss()
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(stringResource(R.string.pingng_desync_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isSupported && !customArgsMissing,
                onClick = {
                    onSave(selectedProfile, customArgs)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun profileHintRes(profile: String): Int = when (profile) {
    PingNgCompat.PROFILE_LIGHT -> R.string.pingng_profile_light_hint
    PingNgCompat.PROFILE_BALANCED -> R.string.pingng_profile_balanced_hint
    PingNgCompat.PROFILE_SEVERE -> R.string.pingng_profile_severe_hint
    PingNgCompat.PROFILE_ADAPTIVE -> R.string.pingng_profile_adaptive_hint
    PingNgCompat.PROFILE_CUSTOM -> R.string.pingng_profile_custom_hint
    else -> R.string.pingng_profile_off_hint
}

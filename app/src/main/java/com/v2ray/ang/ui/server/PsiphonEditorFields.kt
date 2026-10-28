package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

@Composable
fun PsiphonEditorFields(
    enabled: Boolean,
    torEnabled: Boolean = false,
    torBridgeMode: String = "auto",
    onTorBridgeModeChange: (String) -> Unit = {},
    torBridgeSources: String = "auto",
    onTorBridgeSourcesChange: (String) -> Unit = {},
    torBridgeLines: String = "",
    onTorBridgeLinesChange: (String) -> Unit = {},
    region: String,
    onEnabledChange: (Boolean) -> Unit,
    onTorEnabledChange: (Boolean) -> Unit = {},
    onRegionChange: (String) -> Unit,
    enabledTitle: String? = null,
    torEnabledTitle: String? = null,
    mode: String = "auto",
    onModeChange: (String) -> Unit = {},
    cdnIps: String = "",
    onCdnIpsChange: (String) -> Unit = {},
    cdnSni: String = "",
    onCdnSniChange: (String) -> Unit = {},
    cdnSets: String = "",
    onCdnSetsChange: (String) -> Unit = {},
    showTor: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsSwitchItem(
                title = enabledTitle ?: stringResource(R.string.pingng_psiphon_enabled),
                checked = enabled,
                onCheckedChange = onEnabledChange,
                contentVerticalPadding = 12.dp,
            )
            if (showTor) {
                SettingsSwitchItem(
                    title = torEnabledTitle ?: enabledTitle?.replace("Psiphon", "Tor")
                        ?: stringResource(R.string.pingng_tor_enabled),
                    checked = torEnabled,
                    onCheckedChange = onTorEnabledChange,
                    contentVerticalPadding = 12.dp,
                )
            }
        }
        if (enabled || torEnabled) {
            if (torEnabled) {
                val bridgeModeLabels = stringArrayResource(R.array.pingng_tor_bridge_mode_entries)
                val bridgeModeValues = stringArrayResource(R.array.pingng_tor_bridge_mode_values)
                val bridgeModeIndex = bridgeModeValues.indexOf(torBridgeMode.lowercase()).coerceAtLeast(0)
                FormDropdownField(
                    label = stringResource(R.string.pingng_tor_bridge),
                    value = bridgeModeLabels.getOrElse(bridgeModeIndex) { bridgeModeLabels[0] },
                    options = bridgeModeLabels.toList(),
                    onValueChange = { selected ->
                        val index = bridgeModeLabels.indexOf(selected)
                        if (index >= 0) onTorBridgeModeChange(bridgeModeValues[index])
                    },
                )
                when (torBridgeMode.lowercase()) {
                    "auto", "first" -> {
                        val sourceLabels = stringArrayResource(R.array.pingng_tor_bridge_source_entries)
                        val sourceValues = stringArrayResource(R.array.pingng_tor_bridge_source_values)
                        val sourceIndex = sourceValues.indexOf(torBridgeSources.lowercase()).coerceAtLeast(0)
                        FormDropdownField(
                            label = stringResource(R.string.pingng_tor_bridge_sources),
                            value = sourceLabels.getOrElse(sourceIndex) { sourceLabels[0] },
                            options = sourceLabels.toList(),
                            onValueChange = { selected ->
                                val index = sourceLabels.indexOf(selected)
                                if (index >= 0) onTorBridgeSourcesChange(sourceValues[index])
                            },
                        )
                    }
                    "own" -> FormTextField(
                        label = stringResource(R.string.pingng_tor_bridge_lines),
                        value = torBridgeLines,
                        onValueChange = onTorBridgeLinesChange,
                        placeholder = stringResource(R.string.pingng_tor_bridge_lines_hint),
                        maxLines = 6,
                    )
                }
            }
            val modeAutoLabel = stringResource(R.string.pingng_psiphon_mode_auto)
            val modeCdnLabel = stringResource(R.string.pingng_psiphon_mode_cdn)
            val modeDirectLabel = stringResource(R.string.pingng_psiphon_mode_direct)
            if (enabled) FormDropdownField(
                label = stringResource(R.string.pingng_psiphon_mode),
                value = when (mode.lowercase()) {
                    "cdn" -> modeCdnLabel
                    "direct" -> modeDirectLabel
                    else -> modeAutoLabel
                },
                options = listOf(modeAutoLabel, modeCdnLabel, modeDirectLabel),
                onValueChange = { selected ->
                    onModeChange(
                        when (selected) {
                            modeCdnLabel -> "cdn"
                            modeDirectLabel -> "direct"
                            else -> "auto"
                        },
                    )
                },
                supportingText = stringResource(R.string.pingng_psiphon_mode_summary),
            )
            if (enabled && mode.lowercase() != "direct") {
                FormTextField(
                    label = stringResource(R.string.pingng_psiphon_cdn_ips),
                    value = cdnIps,
                    onValueChange = onCdnIpsChange,
                    placeholder = stringResource(R.string.pingng_psiphon_cdn_list_hint),
                )
                if (cdnIps.isNotBlank()) {
                    FormTextField(
                        label = stringResource(R.string.pingng_psiphon_cdn_sni),
                        value = cdnSni,
                        onValueChange = onCdnSniChange,
                        placeholder = stringResource(R.string.pingng_psiphon_cdn_list_hint),
                    )
                }
                val selectedSets = PsiphonCdnSet.parse(cdnSets)
                val cdnSetLabels = stringArrayResource(R.array.pingng_psiphon_cdn_set_entries)
                var expanded by rememberSaveable { mutableStateOf(selectedSets.isNotEmpty()) }
                CollapsiblePreferenceGroupHeader(
                    title = stringResource(R.string.pingng_psiphon_cdn_sets),
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                )
                Text(
                    text = if (selectedSets.isEmpty()) stringResource(R.string.pingng_psiphon_cdn_sets_all)
                    else selectedSets.joinToString(", ") { set -> cdnSetLabels.getOrElse(set.ordinal) { set.key } },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (expanded) {
                    Text(
                        text = stringResource(R.string.pingng_psiphon_cdn_sets_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    PsiphonCdnSet.entries.forEach { set ->
                        val checked = set in selectedSets
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = checked,
                                onValueChange = { chosen ->
                                    val updated = if (chosen) selectedSets + set else selectedSets - set
                                    onCdnSetsChange(PsiphonCdnSet.join(updated).orEmpty())
                                },
                            ).padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(cdnSetLabels.getOrElse(set.ordinal) { set.key }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            FormDropdownField(
                label = if (torEnabled) "Tor exit country" else stringResource(R.string.pingng_psiphon_region),
                value = PsiphonRegions.displayName(region),
                options = PsiphonRegions.displayOptions(),
                onValueChange = { onRegionChange(PsiphonRegions.codeOf(it)) },
                supportingText = if (torEnabled) null else stringResource(R.string.pingng_psiphon_region_summary)
            )
        }
    }
}

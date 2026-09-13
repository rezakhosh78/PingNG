package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

@Composable
fun PsiphonEditorFields(
    enabled: Boolean,
    region: String,
    onEnabledChange: (Boolean) -> Unit,
    onRegionChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSwitchItem(
            title = stringResource(R.string.pingng_psiphon_enabled),
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
        Text(
            text = stringResource(R.string.pingng_psiphon_short_hint),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (enabled) {
            FormDropdownField(
                label = stringResource(R.string.pingng_psiphon_region),
                value = PsiphonRegions.displayName(region),
                options = PsiphonRegions.displayOptions(),
                onValueChange = { onRegionChange(PsiphonRegions.codeOf(it)) },
                supportingText = stringResource(R.string.pingng_psiphon_region_summary)
            )
        }
    }
}

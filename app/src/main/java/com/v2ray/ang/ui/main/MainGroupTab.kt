package com.v2ray.ang.ui.main

import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import com.v2ray.ang.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.dto.GroupMapItem

private val TAB_BAR_PADDING = 2.dp
private val TAB_SPACING = 6.dp
private val TAB_HEIGHT = 34.dp
private val SELECTED_TAB_MARGIN = 8.dp

internal fun groupTabLabel(group: GroupMapItem): String = group.remarks

/** Compact, scrollable group pills with counts; server paging remains owned by MainScreen. */
@Composable
fun GroupTabBar(
    groups: List<GroupMapItem>,
    selectedTabIndex: Int,
    onTabClick: (Int) -> Unit,
    onEditGroup: (String) -> Unit = {},
    onDeleteGroup: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return
    val selectedIndex = selectedTabIndex.coerceIn(0, groups.lastIndex)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(TAB_BAR_PADDING)
        ) {
            NaturalTabsLayout {
                groups.forEachIndexed { index, group ->
                    key(group.id) {
                        GroupTabItem(
                            group = group,
                            selected = index == selectedIndex,
                            onClick = { onTabClick(index) },
                            hasMenu = group.id.isNotBlank(),
                            onEditGroup = onEditGroup,
                            onDeleteGroup = onDeleteGroup,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NaturalTabsLayout(
    content: @Composable () -> Unit
) {
    Layout(content = content) { measurables, _ ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}

        val spacing = TAB_SPACING.roundToPx()
        val tabHeight = TAB_HEIGHT.roundToPx()
        val widths = measurables.map { it.maxIntrinsicWidth(tabHeight) }

        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(widths[index], tabHeight))
        }
        layout(widths.sum() + spacing * (placeables.size - 1), tabHeight) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width + spacing
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTabItem(
    group: GroupMapItem,
    selected: Boolean,
    onClick: () -> Unit,
    hasMenu: Boolean,
    onEditGroup: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bringIntoView = remember(group.id) { BringIntoViewRequester() }
    var size by remember(group.id) { mutableStateOf(IntSize.Zero) }
    var menuExpanded by remember(group.id) { mutableStateOf(false) }
    val marginPx = with(LocalDensity.current) { SELECTED_TAB_MARGIN.toPx() }
    val groupOptionsLabel = stringResource(R.string.group_options)
    val editLabel = stringResource(R.string.action_edit)
    val deleteLabel = stringResource(R.string.action_delete)

    LaunchedEffect(selected, size) {
        if (selected && size != IntSize.Zero) {
            bringIntoView.bringIntoView(
                Rect(-marginPx, 0f, size.width + marginPx, size.height.toFloat())
            )
        }
    }

    val tabColors = if (selected) {
        colors.primary to colors.onPrimary
    } else {
        colors.surfaceContainerLow to colors.onSurfaceVariant
    }
    // Keep the upstream tab constraints on the pill while anchoring its popup.
    Box(propagateMinConstraints = true) {
        Row(
            modifier = Modifier
                .bringIntoViewRequester(bringIntoView)
                .onSizeChanged { size = it }
                .clip(CircleShape)
                .background(tabColors.first)
                .combinedClickable(
                    role = Role.Tab,
                    onClick = onClick,
                    onLongClickLabel = if (hasMenu) groupOptionsLabel else null,
                    onLongClick = if (hasMenu) ({ menuExpanded = true }) else null,
                )
                .semantics { this.selected = selected }
                .semantics {
                    contentDescription = if (group.id.isEmpty()) group.remarks
                    else "${group.remarks}, ${group.serverCount}"
                }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = groupTabLabel(group),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = tabColors.second,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (group.id.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.onPrimary.copy(alpha = 0.2f) else colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = group.serverCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = when {
                            group.serverCount >= 1000 -> 8.sp
                            group.serverCount >= 100 -> 9.sp
                            else -> 10.sp
                        },
                        color = if (selected) colors.onPrimary else colors.onPrimaryContainer,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.widthIn(min = 132.dp),
        ) {
            DropdownMenuItem(
                text = { Text(editLabel) },
                onClick = {
                    menuExpanded = false
                    onEditGroup(group.id)
                },
            )
            DropdownMenuItem(
                text = { Text(deleteLabel) },
                onClick = {
                    menuExpanded = false
                    onDeleteGroup(group.id)
                },
            )
        }
    }
}

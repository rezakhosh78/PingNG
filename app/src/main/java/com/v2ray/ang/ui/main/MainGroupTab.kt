package com.v2ray.ang.ui.main

import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import com.v2ray.ang.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

private val TAB_BAR_PADDING = 3.dp
private val TAB_SPACING = 2.dp
private val TAB_HEIGHT = 30.dp
private val SELECTED_TAB_MARGIN = 12.dp

internal fun groupTabLabel(group: GroupMapItem): String =
    if (group.id.isEmpty()) group.remarks else "${group.remarks} (${group.serverCount})"

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
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = CircleShape,
        color = if (dark) colors.surfaceContainerHigh else colors.surface,
        border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = if (dark) 0.6f else 0.35f)),
        shadowElevation = 3.dp
    ) {
        BoxWithConstraints {
            val innerPaddingPx = with(LocalDensity.current) { TAB_BAR_PADDING.roundToPx() }
            val availablePx = constraints.maxWidth - 2 * innerPaddingPx
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(TAB_BAR_PADDING)
            ) {
                EqualOrNaturalTabsLayout(availablePx = availablePx) {
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
}

@Composable
private fun EqualOrNaturalTabsLayout(
    availablePx: Int,
    content: @Composable () -> Unit
) {
    Layout(content = content) { measurables, _ ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}

        val spacing = TAB_SPACING.roundToPx()
        val tabHeight = TAB_HEIGHT.roundToPx()
        val count = measurables.size
        val gaps = spacing * (count - 1)
        val natural = measurables.map { it.maxIntrinsicWidth(tabHeight) }
        val equalShare = (availablePx - gaps) / count

        val widths = when {
            availablePx <= 0 -> natural
            natural.all { it <= equalShare } -> List(count) { equalShare }
            natural.sum() + gaps <= availablePx -> {
                val extra = (availablePx - gaps - natural.sum()) / count
                natural.map { it + extra }
            }
            else -> natural
        }

        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(widths[index], tabHeight))
        }
        layout(widths.sum() + gaps, tabHeight) {
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

    val count = if (group.id.isEmpty()) 0 else group.serverCount
    // Keep the upstream tab constraints on the pill while anchoring its popup.
    Box(propagateMinConstraints = true) {
        Row(
            modifier = Modifier
                .bringIntoViewRequester(bringIntoView)
                .onSizeChanged { size = it }
                .clip(CircleShape)
                .background(if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                .combinedClickable(
                    role = Role.Tab,
                    onClick = onClick,
                    onLongClickLabel = if (hasMenu) groupOptionsLabel else null,
                    onLongClick = if (hasMenu) ({ menuExpanded = true }) else null,
                )
                .semantics { this.selected = selected }
                .semantics { contentDescription = groupTabLabel(group) }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.remarks,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (selected) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (count > 0) {
                Spacer(Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = if (selected) colors.onPrimary else colors.surface,
                        maxLines = 1
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

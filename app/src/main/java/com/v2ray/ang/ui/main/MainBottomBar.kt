package com.v2ray.ang.ui.main

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    isConnected: Boolean,
    hasSelectedConfig: Boolean,
    scanSteps: List<String>,
    scanEndpoints: List<String>,
    scanScannedCount: Long,
    scanTotalCount: Long,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveScanText = if (scanSteps.isNotEmpty()) {
        if (scanTotalCount > 0) {
            "Scanning endpoints…\nScanned: $scanScannedCount/$scanTotalCount · Found: ${scanEndpoints.size}"
        } else {
            "Scanning endpoints…\nFound: ${scanEndpoints.size}"
        }
    } else {
        displayText
    }
    val colors = MaterialTheme.colorScheme
    val actionLabel = stringResource(if (isRunning) R.string.acc_stop else R.string.acc_start)
    val isDarkTheme = colors.surface.luminance() < 0.5f
    val barColor = if (isDarkTheme) colors.surfaceContainerHigh else colors.surfaceVariant
    val barContentColor = if (isDarkTheme) colors.onSurface else colors.onSurfaceVariant
    val barShape = RoundedCornerShape(26.dp)
    val barBorderColor = colors.outline
    val actionButtonColor = if (isDarkTheme) colors.surface else colors.primary
    val actionIconColor = if (isDarkTheme) colors.onSurface else colors.onPrimary
    val actionBorderColor = if (isDarkTheme) colors.outlineVariant else colors.primary
    val statusColor = when {
        !hasSelectedConfig -> colors.primary
        isConnected -> if (isDarkTheme) Color(0xFF75E5A1) else Color(0xFF168A47)
        else -> if (isDarkTheme) Color(0xFFFF8A80) else Color(0xFFC62828)
    }
    val pulseAlpha = if (hasSelectedConfig) {
        val transition = rememberInfiniteTransition(label = "connection status pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.42f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "connection status opacity",
        )
        alpha
    } else 1f
    val rotationTransition = androidx.compose.animation.core.rememberInfiniteTransition(
        label = "connection button rotation"
    )
    val iconRotation by rotationTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connection button angle",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, barBorderColor, barShape),
            shape = barShape,
            color = barColor,
            contentColor = barContentColor,
            tonalElevation = 1.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClick = { onAction(MainAction.TestCurrentServer) })
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ConnectionStateIndicator(color = statusColor, alpha = pulseAlpha)
                Text(
                    text = liveScanText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = liveScanText },
                    maxLines = 2,
                    textAlign = TextAlign.Start,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(actionButtonColor)
                        .border(1.dp, actionBorderColor, CircleShape)
                        .clickable(role = Role.Button) { onAction(serviceFabAction(isRunning)) }
                        .semantics { contentDescription = actionLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp),
                        contentDescription = actionLabel,
                        tint = actionIconColor,
                        modifier = Modifier.size(22.dp).graphicsLayer {
                            rotationZ = if (isRunning && !isConnected) iconRotation else 0f
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionStateIndicator(color: Color, alpha: Float) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
            .size(14.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(color)
        )
    }
}

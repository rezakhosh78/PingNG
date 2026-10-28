package com.v2ray.ang.ui.main

import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.core.SoarBridge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import com.v2ray.ang.R
import com.v2ray.ang.core.AwgWarpConfig
import com.v2ray.ang.core.PingNgCompat
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.core.WarpPlusFinalMaskPolicy
import com.v2ray.ang.core.WarpWireGuardConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.PsiphonStatus
import com.v2ray.ang.dto.TorStatus
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.ReorderableGridItem
import com.v2ray.ang.ui.compose.ReorderableListItem
import com.v2ray.ang.ui.compose.colorPing
import com.v2ray.ang.ui.compose.colorPingRed
import com.v2ray.ang.ui.compose.configuredCountryCode
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.Utils
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs

@Composable
fun GroupPagerPage(
    groupId: String,
    subscriptionRemarks: String,
    notice: String?,
    supportUrl: String?,
    trafficTotalBytes: Long,
    trafficUsedBytes: Long,
    expirationEpochSeconds: Long,
    trafficPeriodDays: Int,
    trafficTotalRequests: Long,
    trafficUsedRequests: Long,
    usesLocalTrafficGraph: Boolean,
    hasSubscriptionLink: Boolean,
    isDefaultSubscription: Boolean,
    isUpdatingSubscription: Boolean,
    isTestingSubscription: Boolean,
    onUpdateSubscription: (() -> Unit)?,
    onTestSubscriptionRealDelay: (() -> Unit)?,
    onConfigureTrafficGraph: (Long, Int) -> Unit,
    onResetTrafficGraph: () -> Unit,
    mainViewModel: MainViewModel,
    selectedGuid: String?,
    countryCode: String?,
    ipAddress: String?,
    psiphonStates: Map<String, String>,
    torStates: Map<String, String>,
    doubleColumnDisplay: Boolean,
    calendarMode: String,
    confirmRemove: Boolean,
    searchQuery: String,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit,
    contentPadding: PaddingValues
) {
    val serverFlow = remember(groupId) {
        mainViewModel.serversForGroup(groupId)
    }
    val servers by serverFlow.collectAsStateWithLifecycle()
    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        if (shouldShowGroupNotice(hasSubscriptionLink, isDefaultSubscription)) {
            SubscriptionNoticeBanner(subscriptionRemarks, notice, isDefaultSubscription)
        }
        SubscriptionMetadataRow(
            supportUrl = supportUrl,
            trafficTotalBytes = trafficTotalBytes,
            trafficUsedBytes = trafficUsedBytes,
            expirationEpochSeconds = expirationEpochSeconds,
            calendarMode = calendarMode,
            trafficPeriodDays = trafficPeriodDays,
            trafficTotalRequests = trafficTotalRequests,
            trafficUsedRequests = trafficUsedRequests,
            usesLocalTrafficGraph = usesLocalTrafficGraph,
            isDefaultSubscription = isDefaultSubscription,
            isUpdatingSubscription = isUpdatingSubscription,
            isTestingSubscription = isTestingSubscription,
            onUpdateSubscription = onUpdateSubscription,
            onTestSubscriptionRealDelay = onTestSubscriptionRealDelay,
            onConfigureTrafficGraph = onConfigureTrafficGraph,
            onResetTrafficGraph = onResetTrafficGraph,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            ServerListPage(
                servers = servers,
                selectedGuid = selectedGuid,
                countryCode = countryCode,
                ipAddress = ipAddress,
                psiphonStates = psiphonStates,
                torStates = torStates,
                canReorder = canReorder,
                doubleColumnDisplay = doubleColumnDisplay,
                subscriptionId = groupId,
                confirmRemove = confirmRemove,
                groupId = groupId,
                lazyListStates = lazyListStates,
                lazyGridStates = lazyGridStates,
                onSelectServer = onSelectServer,
                onEditServer = onEditServer,
                onShareServer = onShareServer,
                onMoreServer = onMoreServer,
                onRemoveServer = onRemoveServer,
                onMoveServer = { fromIndex, toIndex -> mainViewModel.moveServer(groupId, fromIndex, toIndex) },
                contentPadding = contentPadding
            )
        }
    }
}

@Composable
private fun SubscriptionNoticeBanner(
    subscriptionRemarks: String,
    notice: String?,
    isDefaultSubscription: Boolean,
) {
    val title = if (isDefaultSubscription) {
        stringResource(R.string.subscription_default_notice_title)
    } else {
        "📢 ${subscriptionRemarks.ifBlank { stringResource(R.string.title_sub_update) }}"
    }
    val noticeText = if (isDefaultSubscription) {
        stringResource(R.string.subscription_default_notice_message)
    } else {
        notice?.trim()?.takeIf { it.isNotEmpty() }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            noticeText?.let { text ->
                Text(
                    text = subscriptionNoticeText(text),
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Renders simple **bold** spans used by built-in subscription notices. */
private fun subscriptionNoticeText(text: String) = buildAnnotatedString {
    val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
    var cursor = 0
    boldPattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.groupValues[1])
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
private fun SubscriptionMetadataRow(
    supportUrl: String?,
    trafficTotalBytes: Long,
    trafficUsedBytes: Long,
    expirationEpochSeconds: Long,
    calendarMode: String,
    trafficPeriodDays: Int,
    trafficTotalRequests: Long,
    trafficUsedRequests: Long,
    usesLocalTrafficGraph: Boolean,
    isDefaultSubscription: Boolean,
    isUpdatingSubscription: Boolean,
    isTestingSubscription: Boolean,
    onUpdateSubscription: (() -> Unit)?,
    onTestSubscriptionRealDelay: (() -> Unit)?,
    onConfigureTrafficGraph: (Long, Int) -> Unit,
    onResetTrafficGraph: () -> Unit,
) {
    val context = LocalContext.current
    var showTrafficDialog by rememberSaveable { mutableStateOf(false) }
    var trafficLimitInput by rememberSaveable(trafficTotalBytes) {
        mutableStateOf(formatGigabytes(trafficTotalBytes.coerceAtLeast(0L)))
    }
    var trafficPeriodInput by rememberSaveable(trafficPeriodDays) {
        mutableStateOf(trafficPeriodDays.toString())
    }
    val isRequestQuota = trafficTotalRequests > 0L && trafficUsedRequests >= 0L
    val hasByteQuota = trafficTotalBytes >= 0L && trafficUsedBytes >= 0L
    val hasUsage = isRequestQuota || hasByteQuota
    val canConfigureTrafficGraph = isDefaultSubscription || usesLocalTrafficGraph
    // Some Worker/Pages and ServerLess endpoints do not expose a quota header. Keep the
    // row visually occupied until real counters are available, without
    // pretending that a numeric quota was received.
    val hasWorkerPlaceholder = usesLocalTrafficGraph && !hasUsage
    val hasDefaultPlaceholder = isDefaultSubscription && !hasUsage
    val shouldShowUsage = hasUsage || hasWorkerPlaceholder || hasDefaultPlaceholder
    // -1 means that the provider did not advertise an expiry. Zero is an
    // explicit never-expire value and must be rendered as ∞. Worker links
    // and Pages links default to never expiring when the provider omits an expiry. The same
    // default is used for the bundled ServerLess subscription.
    val displayExpirationEpochSeconds = if (usesLocalTrafficGraph && expirationEpochSeconds < 0L) {
        0L
    } else {
        expirationEpochSeconds
    }
    val hasExpiry = displayExpirationEpochSeconds >= 0L
    if (supportUrl == null && !shouldShowUsage && !hasExpiry && onUpdateSubscription == null &&
        onTestSubscriptionRealDelay == null
    ) return

    val usedFraction = when {
        isRequestQuota -> trafficUsedRequests.toFloat() / trafficTotalRequests.toFloat()
        hasByteQuota && trafficTotalBytes > 0L -> trafficUsedBytes.toFloat() / trafficTotalBytes.toFloat()
        canConfigureTrafficGraph && hasByteQuota && trafficTotalBytes == 0L ->
            defaultTrafficUsageFraction(trafficUsedBytes)
        else -> 0f
    }
    val usageText = when {
        isRequestQuota -> context.getString(
            R.string.subscription_requests_usage,
            formatCount(trafficUsedRequests),
            formatCount(trafficTotalRequests),
        )
        hasByteQuota -> context.getString(
            if (trafficTotalBytes == 0L) R.string.subscription_unlimited_usage else R.string.subscription_bytes_usage,
            formatGigabytes(trafficUsedBytes),
            formatGigabytes(trafficTotalBytes),
        )
        hasWorkerPlaceholder -> context.getString(
            R.string.subscription_unlimited_usage,
            "0",
            "0",
        )
        hasDefaultPlaceholder -> context.getString(R.string.subscription_default_no_quota)
        else -> null
    }
    val expirationText = if (isDefaultSubscription) {
        context.getString(R.string.subscription_default_expiry)
    } else if (hasExpiry) {
        context.getString(
            R.string.subscription_expiry,
            formatExpirationDate(displayExpirationEpochSeconds, context, calendarMode),
        )
    } else {
        null
    }

    // Keep the metadata row LTR so the support icon is always on the right,
    // including when the application is using Persian RTL layout.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 3.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.End,
        ) {
            if (shouldShowUsage || hasExpiry) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = if (supportUrl != null) 10.dp else 0.dp),
                ) {
                    if (shouldShowUsage) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .clickable(
                                    enabled = canConfigureTrafficGraph,
                                    role = Role.Button,
                                    onClick = {
                                        trafficLimitInput = formatGigabytes(trafficTotalBytes.coerceAtLeast(0L))
                                        trafficPeriodInput = trafficPeriodDays.toString()
                                        showTrafficDialog = true
                                    },
                                )
                                .semantics {
                                    if (canConfigureTrafficGraph) {
                                        contentDescription = context.getString(
                                            R.string.subscription_default_traffic_edit,
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            LinearProgressIndicator(
                                progress = { usedFraction.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                    val periodText = if (canConfigureTrafficGraph) {
                        if (trafficPeriodDays > 0) {
                            context.getString(
                                R.string.subscription_default_traffic_period_value,
                                trafficPeriodDays,
                            )
                        } else {
                            context.getString(R.string.subscription_default_traffic_period_never)
                        }
                    } else {
                        null
                    }
                    if (usageText != null || periodText != null || expirationText != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            usageText?.let {
                                Text(
                                    text = it,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Start,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (usageText == null) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            periodText?.let {
                                val isPersian = context.resources.configuration.locales[0].language == "fa"
                                CompositionLocalProvider(
                                    LocalLayoutDirection provides if (isPersian) {
                                        LayoutDirection.Rtl
                                    } else {
                                        LayoutDirection.Ltr
                                    }
                                ) {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            expirationText?.let {
                                val isPersian = context.resources.configuration.locales[0].language == "fa"
                                CompositionLocalProvider(
                                    LocalLayoutDirection provides if (isPersian) {
                                        LayoutDirection.Rtl
                                    } else {
                                        LayoutDirection.Ltr
                                    }
                                ) {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            onUpdateSubscription?.let { updateSubscription ->
                IconButton(
                    onClick = updateSubscription,
                    enabled = !isUpdatingSubscription,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restore_24dp),
                        contentDescription = stringResource(R.string.title_sub_update),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            onTestSubscriptionRealDelay?.let { testSubscription ->
                IconButton(
                    onClick = testSubscription,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speed_24dp),
                        contentDescription = stringResource(
                            if (isDefaultSubscription) R.string.title_real_ping_default
                            else R.string.title_real_ping_subscription,
                        ),
                        modifier = Modifier.size(24.dp),
                        tint = if (isTestingSubscription) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (canConfigureTrafficGraph) {
                IconButton(
                    onClick = onResetTrafficGraph,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_reset_24dp),
                        contentDescription = stringResource(R.string.subscription_default_traffic_reset),
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            supportUrl?.let { url ->
                IconButton(
                    onClick = { Utils.openUri(context, url) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_telegram_24dp),
                        contentDescription = stringResource(R.string.subscription_support_url),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    if (showTrafficDialog && canConfigureTrafficGraph) {
        val limitBytes = trafficLimitInput.trim().toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..100_000.0 }
            ?.let { (it * 1024.0 * 1024.0 * 1024.0).toLong() }
        val periodDays = trafficPeriodInput.trim().toIntOrNull()
            ?.takeIf { it in 0..3650 }
        AlertDialog(
            onDismissRequest = { showTrafficDialog = false },
            title = { Text(stringResource(R.string.subscription_default_traffic_edit)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = trafficLimitInput,
                        onValueChange = { trafficLimitInput = it },
                        label = { Text(stringResource(R.string.subscription_default_traffic_amount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = trafficPeriodInput,
                        onValueChange = { trafficPeriodInput = it },
                        label = { Text(stringResource(R.string.subscription_default_traffic_period)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    if (limitBytes == null || periodDays == null) {
                        Text(
                            stringResource(R.string.subscription_default_traffic_invalid),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = limitBytes != null && periodDays != null,
                    onClick = {
                        val validatedLimit = limitBytes ?: return@TextButton
                        val validatedPeriod = periodDays ?: return@TextButton
                        onConfigureTrafficGraph(validatedLimit, validatedPeriod)
                        showTrafficDialog = false
                    },
                ) { Text(stringResource(R.string.acc_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showTrafficDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun formatGigabytes(bytes: Long): String {
    val gigabytes = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val formatted = String.format(java.util.Locale.US, "%.2f", gigabytes)
    return formatted.trimEnd('0').trimEnd('.')
}

private fun formatCount(value: Long): String {
    return String.format(Locale.US, "%,d", value.coerceAtLeast(0L))
}

private fun formatExpirationDate(
    epochSeconds: Long,
    context: android.content.Context,
    calendarMode: String,
): String {
    if (epochSeconds == 0L) return context.getString(R.string.subscription_unlimited)
    val date = runCatching {
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull() ?: return "-"
    if (calendarMode == AppConfig.CALENDAR_MODE_GREGORIAN) {
        return DateTimeFormatter.ISO_LOCAL_DATE.format(date)
    }
    val jalali = gregorianToJalali(date.year, date.monthValue, date.dayOfMonth)
    if (jalali[1] !in 1..12) return "-"
    val isPersian = context.resources.configuration.locales[0].language == "fa"
    val month = if (isPersian) {
        when (jalali[1]) {
            1 -> "فروردین"
            2 -> "اردیبهشت"
            3 -> "خرداد"
            4 -> "تیر"
            5 -> "مرداد"
            6 -> "شهریور"
            7 -> "مهر"
            8 -> "آبان"
            9 -> "آذر"
            10 -> "دی"
            11 -> "بهمن"
            12 -> "اسفند"
            else -> return "-"
        }
    } else {
        when (jalali[1]) {
            1 -> "Farvardin"
            2 -> "Ordibehesht"
            3 -> "Khordad"
            4 -> "Tir"
            5 -> "Mordad"
            6 -> "Shahrivar"
            7 -> "Mehr"
            8 -> "Aban"
            9 -> "Azar"
            10 -> "Dey"
            11 -> "Bahman"
            12 -> "Esfand"
            else -> return "-"
        }
    }
    return "${jalali[2]} $month ${jalali[0]}"
}

private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): IntArray {
    // Cumulative Gregorian month offsets keep the conversion independent of the
    // displayed month; this avoids the old fixed-month result in the expiry row.
    val gregorianMonthDays = intArrayOf(
        0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334
    )
    val adjustedGregorianYear = if (gm > 2) gy + 1 else gy
    var days = 355666 + (365 * gy) + ((adjustedGregorianYear + 3) / 4) -
        ((adjustedGregorianYear + 99) / 100) + ((adjustedGregorianYear + 399) / 400) +
        gd + gregorianMonthDays[gm - 1]

    var jYear = -1595 + (33 * (days / 12053))
    days %= 12053
    jYear += 4 * (days / 1461)
    days %= 1461
    if (days > 365) {
        jYear += (days - 1) / 365
        days = (days - 1) % 365
    }
    val jMonth = if (days < 186) 1 + (days / 31) else 7 + ((days - 186) / 30)
    val jDay = 1 + if (days < 186) days % 31 else (days - 186) % 30
    return intArrayOf(jYear, jMonth, jDay)
}

@Composable
private fun ServerListPage(
    servers: List<ServersCache>,
    selectedGuid: String?,
    countryCode: String?,
    ipAddress: String?,
    psiphonStates: Map<String, String>,
    torStates: Map<String, String>,
    canReorder: Boolean,
    doubleColumnDisplay: Boolean,
    subscriptionId: String,
    confirmRemove: Boolean,
    groupId: String,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit,
    onMoveServer: (Int, Int) -> Unit,
    contentPadding: PaddingValues
) {
    if (doubleColumnDisplay) {
        val gridState = remember(groupId) {
            lazyGridStates.getOrPut(groupId) { LazyGridState() }
        }
        val reorderableGridState = if (canReorder) {
            rememberReorderableLazyGridState(gridState) { from, to ->
                onMoveServer(from.index, to.index)
            }
        } else null

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(gridState),
            contentPadding = contentPadding
        ) {
            itemsIndexed(items = servers, key = { _, item -> item.guid }) { _, serverCache ->
                val content: @Composable () -> Unit = {
                    ServerItemColumn(
                        serverCache = serverCache,
                        selectedGuid = selectedGuid,
                        countryCode = countryCode,
                        ipAddress = ipAddress,
                        psiphonState = psiphonStates[serverCache.guid],
                        torState = torStates[serverCache.guid],
                        subscriptionId = subscriptionId,
                        doubleColumnDisplay = true,
                        onSelectServer = onSelectServer,
                        onEditServer = onEditServer,
                        onShareServer = onShareServer,
                        onMoreServer = onMoreServer,
                        onRemoveServer = onRemoveServer
                    )
                }
                if (canReorder && reorderableGridState != null) {
                    ReorderableItem(
                        reorderableGridState,
                        key = serverCache.guid
                    ) { isDragging ->
                        ReorderableGridItem(
                            scope = this,
                            isDragging = isDragging
                        ) { content() }
                    }
                } else {
                    content()
                }
            }
        }
    } else {
        val listState = remember(groupId) {
            lazyListStates.getOrPut(groupId) { LazyListState() }
        }
        val reorderableState = if (canReorder) {
            rememberReorderableLazyListState(listState) { from, to ->
                onMoveServer(from.index, to.index)
            }
        } else null

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(listState),
            contentPadding = contentPadding
        ) {
            itemsIndexed(items = servers, key = { _, item -> item.guid }) { _, serverCache ->
                if (canReorder && reorderableState != null) {
                    ReorderableItem(
                        reorderableState,
                        key = serverCache.guid
                    ) { isDragging ->
                        ReorderableListItem(
                            scope = this,
                            isDragging = isDragging
                        ) {
                            ServerItemRow(
                                serverCache = serverCache,
                                selectedGuid = selectedGuid,
                                countryCode = countryCode,
                                ipAddress = ipAddress,
                                psiphonState = psiphonStates[serverCache.guid],
                                torState = torStates[serverCache.guid],
                                subscriptionId = subscriptionId,
                                onSelectServer = onSelectServer,
                                onEditServer = onEditServer,
                                onShareServer = onShareServer,
                                onMoreServer = onMoreServer,
                                onRemoveServer = onRemoveServer
                            )
                        }
                        ItemDivider()
                    }
                } else {
                    ServerItemRow(
                        serverCache = serverCache,
                        selectedGuid = selectedGuid,
                        countryCode = countryCode,
                        ipAddress = ipAddress,
                        psiphonState = psiphonStates[serverCache.guid],
                        torState = torStates[serverCache.guid],
                        subscriptionId = subscriptionId,
                        onSelectServer = onSelectServer,
                        onEditServer = onEditServer,
                        onShareServer = onShareServer,
                        onMoreServer = onMoreServer,
                        onRemoveServer = onRemoveServer
                    )
                    ItemDivider()
                }
            }
        }
    }
}

@Composable
private fun ServerItemRow(
    serverCache: ServersCache,
    selectedGuid: String?,
    countryCode: String?,
    ipAddress: String?,
    psiphonState: String?,
    torState: String?,
    subscriptionId: String,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit
) {
    // Read the latest persisted copy. The daemon writes exit data after the
    // delay probe, while ServersCache may still contain the older snapshot.
    val profile = MmkvManager.decodeServerConfig(serverCache.guid) ?: serverCache.profile
    val subRemarks = if (subscriptionId.isEmpty()) {
        MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            ?.toString() ?: ""
    } else ""

    val isSelected = serverCache.guid == selectedGuid
    val resolvedExitInfo = resolveServerExitInfo(
        isSelected = isSelected,
        liveCountryCode = countryCode,
        liveIpAddress = ipAddress,
        cachedCountryCode = serverCache.profile.lastExitCountryCode,
        cachedIpAddress = serverCache.profile.lastExitIpAddress,
        persistedCountryCode = profile.lastExitCountryCode,
        persistedIpAddress = profile.lastExitIpAddress,
    )
    val openVpnIpv6IsUnverified = profile.configType == EConfigType.OPENVPN &&
        resolvedExitInfo.ipAddress?.contains(':') == true
    val fixedCountryCode = if (openVpnIpv6IsUnverified) {
        null
    } else {
        resolvedExitInfo.countryCode
            ?: configuredCountryCode(profile.psiphonEnabled, profile.psiphonRegion)
    }
    val exitIp = resolvedExitInfo.ipAddress.takeUnless { openVpnIpv6IsUnverified }
    val exitInfo = listOfNotNull(
        com.v2ray.ang.ui.compose.countryFlag(fixedCountryCode),
        exitIp,
    ).joinToString(" ").ifBlank { null }

    ServerListItem(
        remarks = displayProfileRemark(profile),
        countryCode = null,
        statistics = serverStatistics(profile),
        exitInfo = exitInfo,
        typeDescription = getProtocolDescription(profile),
        testDelayMillis = serverCache.testDelayMillis,
        isSelected = isSelected,
        subscriptionRemarks = subRemarks,
        doubleColumnDisplay = false,
        desyncLabelRes = desyncBadgeResource(profile),
        isFinalMaskEnabled = WarpPlusFinalMaskPolicy.shouldShowBadge(profile),
        isPsiphonEnabled = profile.psiphonEnabled,
        isBepassEnabled = profile.bepassEnabled,
        isTorEnabled = profile.torEnabled,
        psiphonState = psiphonState,
        torState = torState,
        onClick = {
            onSelectServer(serverCache.guid)
        },
        onShare = { onShareServer(serverCache.guid, profile) },
        onEdit = { onEditServer(serverCache.guid, profile) },
        onRemove = { onRemoveServer(serverCache.guid) },
        onMore = { onMoreServer(serverCache.guid, profile) },
    )
}

@Composable
private fun ServerItemColumn(
    serverCache: ServersCache,
    selectedGuid: String?,
    countryCode: String?,
    ipAddress: String?,
    psiphonState: String?,
    torState: String?,
    subscriptionId: String,
    doubleColumnDisplay: Boolean,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit
) {
    val profile = MmkvManager.decodeServerConfig(serverCache.guid) ?: serverCache.profile
    val subRemarks = if (subscriptionId.isEmpty()) {
        MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()?.toString() ?: ""
    } else ""
    val isSelected = serverCache.guid == selectedGuid
    val resolvedExitInfo = resolveServerExitInfo(
        isSelected = isSelected,
        liveCountryCode = countryCode,
        liveIpAddress = ipAddress,
        cachedCountryCode = serverCache.profile.lastExitCountryCode,
        cachedIpAddress = serverCache.profile.lastExitIpAddress,
        persistedCountryCode = profile.lastExitCountryCode,
        persistedIpAddress = profile.lastExitIpAddress,
    )
    val openVpnIpv6IsUnverified = profile.configType == EConfigType.OPENVPN &&
        resolvedExitInfo.ipAddress?.contains(':') == true
    val fixedCountryCode = if (openVpnIpv6IsUnverified) {
        null
    } else {
        resolvedExitInfo.countryCode
            ?: configuredCountryCode(profile.psiphonEnabled, profile.psiphonRegion)
    }
    val exitIp = resolvedExitInfo.ipAddress.takeUnless { openVpnIpv6IsUnverified }
    val exitInfo = listOfNotNull(
        com.v2ray.ang.ui.compose.countryFlag(fixedCountryCode),
        exitIp,
    ).joinToString(" ").ifBlank { null }
    Column {
        ServerListItem(
            remarks = displayProfileRemark(profile),
            countryCode = null,
            statistics = serverStatistics(profile),
            exitInfo = exitInfo,
            typeDescription = getProtocolDescription(profile),
            testDelayMillis = serverCache.testDelayMillis,
            isSelected = isSelected,
            subscriptionRemarks = subRemarks,
            doubleColumnDisplay = doubleColumnDisplay,
            desyncLabelRes = desyncBadgeResource(profile),
            isFinalMaskEnabled = WarpPlusFinalMaskPolicy.shouldShowBadge(profile),
            isPsiphonEnabled = profile.psiphonEnabled,
            isBepassEnabled = profile.bepassEnabled,
            isTorEnabled = profile.torEnabled,
            psiphonState = psiphonState,
            torState = torState,
            onClick = {
                onSelectServer(serverCache.guid)
            },
            onEdit = { onEditServer(serverCache.guid, profile) },
            onShare = { onShareServer(serverCache.guid, profile) },
            onRemove = { onRemoveServer(serverCache.guid) },
            onMore = { onMoreServer(serverCache.guid, profile) },
        )
        ItemDivider()
    }
}

@Composable
fun ServerListItem(
    remarks: String,
    countryCode: String?,
    statistics: String,
    exitInfo: String?,
    typeDescription: String,
    testDelayMillis: Long,
    isSelected: Boolean,
    subscriptionRemarks: String,
    doubleColumnDisplay: Boolean,
    desyncLabelRes: Int?,
    isFinalMaskEnabled: Boolean,
    isPsiphonEnabled: Boolean,
    isBepassEnabled: Boolean,
    isTorEnabled: Boolean,
    psiphonState: String?,
    torState: String?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier
) {
    val isPersian = LocalContext.current.resources.configuration.locales[0].language == "fa"
    val cardLayoutDirection = if (isPersian) LayoutDirection.Rtl else LayoutDirection.Ltr
    val testResult = if (testDelayMillis == 0L) {
        ""
    } else {
        stringResource(R.string.server_test_delay_value, testDelayMillis)
    }
    val selectedStateDescription = if (isSelected) {
        stringResource(R.string.acc_selected_server)
    } else {
        null
    }
    val hasBadges = desyncLabelRes != null || isPsiphonEnabled || isBepassEnabled ||
        isTorEnabled || isFinalMaskEnabled
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics {
                if (selectedStateDescription != null) {
                    stateDescription = selectedStateDescription
                }
            }
            .clickable(onClick = onClick)
            .then(dragModifier)
    ) {
        Box(
            Modifier
                .width(10.dp)
                .fillMaxHeight()
        ) {
            if (isSelected) {
                Row {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .padding(vertical = 10.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    com.v2ray.ang.ui.compose.countryFlag(countryCode)?.let { flag ->
                        Text(flag, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 6.dp))
                    }
                    Text(
                        remarks,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (doubleColumnDisplay) {
                    IconButton(onClick = onMore, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert_24dp),
                            stringResource(R.string.acc_more),
                            Modifier.size(24.dp)
                        )
                    }
                } else {
                    IconButton(onClick = onShare, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_share_24dp),
                            stringResource(R.string.title_configuration_share),
                            Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onEdit, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_edit_24dp),
                            stringResource(R.string.acc_edit),
                            Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onRemove, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_delete_24dp),
                            stringResource(R.string.acc_delete),
                            Modifier.size(24.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (subscriptionRemarks.isNotBlank()) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), Alignment.Center
                    ) {
                        Text(subscriptionRemarks.take(1).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(statistics, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CompositionLocalProvider(LocalLayoutDirection provides cardLayoutDirection) {
                if (exitInfo != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    if (hasBadges) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = exitInfo,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (testResult.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = testResult,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (testDelayMillis < 0L) colorPingRed else colorPing,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = exitInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (hasBadges) {
                    // With an exit IP, ping shares its row on the opposite side.
                    // Without an IP, keep ping immediately above protocol/badges.
                    if (exitInfo != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                    } else if (testResult.isNotBlank()) {
                        Text(
                            testResult,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testDelayMillis < 0L) colorPingRed else colorPing,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProtocolTypeLabel(
                            typeDescription = typeDescription,
                            modifier = Modifier.widthIn(max = 170.dp),
                        )
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (desyncLabelRes != null) {
                                Text(
                                    text = stringResource(desyncLabelRes),
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                )
                            }
                            if (isPsiphonEnabled) {
                                ProxyLayerStatusBadge(
                                    label = stringResource(R.string.pingng_psiphon_status),
                                    isConnected = psiphonState == PsiphonStatus.CONNECTED,
                                    isConnecting = psiphonState == PsiphonStatus.CONNECTING,
                                )
                            }
                            if (isBepassEnabled) {
                                val bepassColor = Color(0xFF5E35B1)
                                Text(
                                    text = stringResource(R.string.bepass_label),
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .background(bepassColor.copy(alpha = 0.14f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = bepassColor,
                                    maxLines = 1,
                                )
                            }
                            if (isTorEnabled) {
                                val torConnected = isSelected &&
                                    torState == TorStatus.CONNECTED
                                val torConnecting = isSelected &&
                                    torState == TorStatus.CONNECTING
                                ProxyLayerStatusBadge(
                                    label = "Tor",
                                    isConnected = torConnected,
                                    isConnecting = torConnecting,
                                )
                            }
                            if (isFinalMaskEnabled) {
                                val finalMaskColor = Color(0xFFEF6C00)
                                Text(
                                    text = "FinalMask",
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .background(finalMaskColor.copy(alpha = 0.14f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = finalMaskColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProtocolTypeLabel(
                            typeDescription = typeDescription,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            testResult,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testDelayMillis < 0L) colorPingRed else colorPing,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolTypeLabel(
    typeDescription: String,
    modifier: Modifier = Modifier,
) {
    if (typeDescription == AwgWarpConfig.DEFAULT_REMARK) {
        Text(
            text = typeDescription,
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF2196F3),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            modifier = modifier,
            text = buildAnnotatedString {
                typeDescription.split(" / ").forEachIndexed { index, part ->
                    if (index > 0) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(" / ")
                        }
                    }
                    withStyle(SpanStyle(color = protocolPartColor(part))) {
                        append(part)
                    }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun desyncBadgeResource(profile: ProfileItem): Int? = when (PingNgCompat.displayMethod(profile)) {
    PingNgCompat.PROFILE_LIGHT -> R.string.pingng_desync_badge_light
    PingNgCompat.PROFILE_BALANCED -> R.string.pingng_desync_badge_balanced
    PingNgCompat.PROFILE_SEVERE -> R.string.pingng_desync_badge_severe
    PingNgCompat.PROFILE_ADAPTIVE -> R.string.pingng_desync_badge_adaptive
    PingNgCompat.METHOD_SPLIT -> R.string.pingng_desync_badge_split
    PingNgCompat.METHOD_DISORDER -> R.string.pingng_desync_badge_disorder
    PingNgCompat.METHOD_FAKE_SNI -> R.string.pingng_desync_badge_fake_sni
    PingNgCompat.METHOD_OUT_OF_BAND -> R.string.pingng_desync_badge_out_of_band
    PingNgCompat.METHOD_DISORDER_OUT_OF_BAND -> R.string.pingng_desync_badge_disorder_out_of_band
    else -> null
}

private fun displayProfileRemark(profile: ProfileItem): String = when {
    profile.remarks == "StormDNS" -> "MasterDNS"
    AwgWarpConfig.isProfile(profile) && profile.remarks in setOf("AWG WARP", "WARP AWG") -> AwgWarpConfig.DEFAULT_REMARK
    else -> profile.remarks
}

private fun getProtocolDescription(profile: ProfileItem): String {
    if (AwgWarpConfig.isProfile(profile)) return AwgWarpConfig.DEFAULT_REMARK
    if (WarpWireGuardConfig.isProfile(profile)) return "WARP WireGuard"
    if (WarpPlusConfig.isDescription(profile.description)) return "WARP PLUS"
    if (WarpMasqueConfig.isDescription(profile.description)) return "WARP MASQUE/H2"
    if (profile.description == "StormDNS") return "MasterDNS"
    if (profile.description == MasterDnsBridge.DNSTT) return dnsttDisplayMode(profile)
    if (profile.description == MasterDnsBridge.LABEL) return MasterDnsBridge.UI_LABEL
    if (SoarBridge.isProfile(profile)) return "SOAR DNS"
    val configTypeName = if (profile.configType == EConfigType.OPENVPN) "OpenVPN" else profile.configType.name
    if (profile.configType.isComplexType()) return configTypeName
    val parts = mutableListOf(configTypeName)
    profile.network?.let { net ->
        if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) parts.add(net)
    }
    profile.security?.let { sec ->
        if (sec.isNotBlank()) {
            if (profile.insecure == true && sec.equals("tls", ignoreCase = true)) {
                parts.add("$sec insecure")
            } else {
                parts.add(sec)
            }
        }
    }
    return parts.joinToString(" / ")
}

private fun serverStatistics(profile: ProfileItem): String = when {
    AwgWarpConfig.isProfile(profile) ->
        listOfNotNull(profile.server?.takeIf { it.isNotBlank() }, profile.serverPort).joinToString(":")
    WarpWireGuardConfig.isProfile(profile) ->
        profile.warpWireGuardSelectedEndpoint?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(profile.server?.takeIf { it.isNotBlank() }, profile.serverPort).joinToString(":")
    WarpPlusConfig.isDescription(profile.description) ->
        warpPlusCurrentEndpoints(profile)
    WarpMasqueConfig.isDescription(profile.description) ->
        profile.warpMasqueSelectedEndpoint?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(profile.server?.takeIf { it.isNotBlank() }, profile.serverPort)
                .joinToString(":")
    MasterDnsBridge.isProfile(profile) ->
        profile.masterDnsDomain?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (profile.description) {
                MasterDnsBridge.DNSTT -> dnsttDisplayMode(profile)
                else -> MasterDnsBridge.UI_LABEL
            }
    SoarBridge.isProfile(profile) -> profile.soarZone?.trim()?.takeIf { it.isNotEmpty() } ?: "SOAR DNS"
    else -> profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
}

private fun formatWarpEndpointDisplay(value: String): String = value
    .split('•')
    .map { it.trim().removePrefix("inner").removePrefix("outer").trim() }
    .filter(String::isNotBlank)
    .joinToString(" • ")

private fun warpPlusCurrentEndpoints(parent: ProfileItem): String {
    val remarks = parent.proxyChainProfiles.orEmpty().split(',')
        .map(String::trim).filter(String::isNotBlank)
    if (remarks.size >= 2) {
        val children = remarks.take(2).map { remark ->
            com.v2ray.ang.handler.SettingsManager.getServerViaRemarks(remark)
        }
        val legacyOuterFirst = parent.description == WarpPlusConfig.LEGACY_DESCRIPTION
        val inner = children.getOrNull(if (legacyOuterFirst) 1 else 0)
        val outer = children.getOrNull(if (legacyOuterFirst) 0 else 1)
        fun endpoint(profile: ProfileItem?): String? {
            val host = profile?.server?.trim()?.removePrefix("[")?.removeSuffix("]")
                ?.takeIf(String::isNotBlank) ?: return null
            val port = profile?.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return "${if (host.contains(':')) "[$host]" else host}:$port"
        }
        val innerEndpoint = endpoint(inner)
        val outerEndpoint = endpoint(outer)
        if (innerEndpoint != null && outerEndpoint != null) {
            return "$innerEndpoint • $outerEndpoint"
        }
    }
    return parent.warpSelectedEndpoint?.takeIf(String::isNotBlank)
        ?.let(::formatWarpEndpointDisplay).orEmpty()
}

private fun dnsttDisplayMode(profile: ProfileItem): String =
    if (profile.dnsTunnelMode.equals("SSH", ignoreCase = true)) "DNSTT SSH" else "DNSTT SOCKS5"

private fun protocolPartColor(part: String): Color {
    val value = part.trim().uppercase()
    return when {
        value == "OPENVPN" -> Color(0xFF1565C0)
        value == AwgWarpConfig.DEFAULT_REMARK.uppercase() -> Color(0xFF2196F3)
        value.startsWith("DNSTT") -> Color(0xFF2196F3)
        value == "MASTERDNS" -> Color(0xFFFF9800)
        value == "SOAR DNS" -> Color(0xFFE91E63)
        value.startsWith("WARP MASQUE") -> Color(0xFF00ACC1)
        value.startsWith("WARP WIREGUARD") -> Color(0xFFFFC107)
        value == "WARP" -> Color(0xFF00ACC1)
        value.startsWith("WARP PLUS") -> Color(0xFFE91E63)
        value.startsWith("VLESS") -> Color(0xFF00B8D4)
        value.startsWith("VMESS") -> Color(0xFF7C4DFF)
        value.startsWith("TROJAN") -> Color(0xFFFF6D00)
        value.startsWith("SHADOWSOCKS") -> Color(0xFF2979FF)
        value.startsWith("SOCKS") -> Color(0xFF00A878)
        value.startsWith("HTTP") -> Color(0xFFF9A825)
        value.startsWith("AMNEZIAWG") -> Color(0xFF7E57C2)
        value.startsWith("WIREGUARD") -> Color(0xFF00ACC1)
        value.startsWith("HYSTERIA") -> Color(0xFFE91E63)
        value == "WS" || value.startsWith("WS ") -> Color(0xFF1E88E5)
        value == "GRPC" || value.startsWith("GRPC ") -> Color(0xFFD81B60)
        value == "HTTPUPGRADE" -> Color(0xFF8E24AA)
        value == "XHTTP" -> Color(0xFF3949AB)
        value.startsWith("TLS") -> Color(0xFF00897B)
        value.startsWith("REALITY") -> Color(0xFF6A1B9A)
        value.startsWith("TCP") -> Color(0xFF546E7A)
        else -> Color(0xFF26A69A)
    }
}

internal suspend fun PagerState.navigateToPageOptimized(
    targetPage: Int,
    animateAdjacentPage: Boolean = true
) {
    if (pageCount <= 0) return
    val target = targetPage.coerceIn(0, pageCount - 1)
    val current = settledPage.coerceIn(0, pageCount - 1)
    if (target == current) return

    if (abs(target - current) == 1 && animateAdjacentPage) {
        animateScrollToPage(target)
    } else {
        scrollToPage(target)
    }
}

@Composable
private fun ProxyLayerStatusBadge(
    label: String,
    isConnected: Boolean,
    isConnecting: Boolean,
) {
    val showConnecting = isConnecting && !isConnected
    var dotCount by remember(showConnecting) { mutableIntStateOf(0) }
    LaunchedEffect(showConnecting) {
        if (!showConnecting) {
            dotCount = 0
            return@LaunchedEffect
        }
        while (true) {
            dotCount = (dotCount + 1) % 4
            delay(450L)
        }
    }

    val statusColor = if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828)
    Text(
        text = label + if (showConnecting) ".".repeat(dotCount) else "",
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(statusColor.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = statusColor,
    )
}

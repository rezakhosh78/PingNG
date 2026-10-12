package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.isProfilePingable
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache

internal fun isDefaultSubscriptionGroup(groupId: String): Boolean =
    groupId == AppConfig.DEFAULT_SUBSCRIPTION_ID

internal fun shouldShowGroupNotice(hasSubscriptionLink: Boolean, isDefaultGroup: Boolean): Boolean =
    hasSubscriptionLink || isDefaultGroup

internal fun canTestGroupRealDelay(hasSubscriptionLink: Boolean, isDefaultGroup: Boolean): Boolean =
    hasSubscriptionLink || isDefaultGroup

internal fun pingableServerGuids(servers: List<ServersCache>): List<String> =
    servers.asSequence()
        .filter { isProfilePingable(it.profile) }
        .map { it.guid }
        .toList()

internal fun visiblePingDelayMillis(profile: ProfileItem, delayMillis: Long): Long =
    if (isProfilePingable(profile)) delayMillis else 0L

internal fun GroupMapItem.withDefaultTrafficUsage(usedBytes: Long): GroupMapItem =
    if (isDefaultSubscriptionGroup(id)) {
        copy(trafficTotalBytes = 0L, trafficUsedBytes = usedBytes.coerceAtLeast(0L))
    } else {
        this
    }

/** Unlimited usage has no quota ratio, so show an asymptotic visual meter beside its exact byte label. */
internal fun defaultTrafficUsageFraction(usedBytes: Long): Float {
    val used = usedBytes.coerceAtLeast(0L).toDouble()
    val reference = 2L * 1024L * 1024L * 1024L
    return (used / (used + reference)).toFloat().coerceIn(0f, 1f)
}

internal fun serviceFabAction(isRunning: Boolean): MainAction =
    if (isRunning) MainAction.StopService else MainAction.ToggleService

package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.core.canDisplayProfilePing
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

internal fun pingableServerGuids(servers: List<ServersCache>, includeOpenVpnProfiles: Boolean = false): List<String> =
    servers.asSequence()
        .filter { server ->
            isProfilePingable(server.profile) ||
                (server.profile.configType == com.v2ray.ang.enums.EConfigType.OPENVPN &&
                    includeOpenVpnProfiles)
        }
        .map { it.guid }
        .toList()

internal fun visiblePingDelayMillis(profile: ProfileItem, delayMillis: Long): Long =
    if (canDisplayProfilePing(profile)) delayMillis else 0L

/** OpenVPN uses its VpnService receiver for the same manual test action as Xray profiles. */
internal fun canCheckCurrentConnection(profile: ProfileItem): Boolean =
    isProfilePingable(profile) || MasterDnsBridge.isProfile(profile) ||
        profile.configType == com.v2ray.ang.enums.EConfigType.OPENVPN

/** Applies a batch of ping results with one list traversal and one UI state update. */
internal fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, Long>,
): List<ServersCache> {
    if (updates.isEmpty()) return servers
    var changed = false
    val result = servers.map { server ->
        val measured = updates[server.guid] ?: return@map server
        val visible = visiblePingDelayMillis(server.profile, measured)
        if (visible == server.testDelayMillis) return@map server
        changed = true
        server.copy(testDelayMillis = visible)
    }
    return if (changed) result else servers
}

/** Clears test results for a batch without rebuilding untouched rows. */
internal fun clearTestDelayResults(
    servers: List<ServersCache>,
    guids: Set<String>,
): List<ServersCache> {
    if (guids.isEmpty()) return servers
    var changed = false
    val result = servers.map { server ->
        if (server.guid !in guids || server.testDelayMillis == 0L) return@map server
        changed = true
        server.copy(testDelayMillis = 0L)
    }
    return if (changed) result else servers
}

internal data class ServerExitInfo(
    val countryCode: String?,
    val ipAddress: String?,
)

/** Current connection-test data overrides older profile values for the selected row. */
internal fun resolveServerExitInfo(
    isSelected: Boolean,
    liveCountryCode: String?,
    liveIpAddress: String?,
    cachedCountryCode: String?,
    cachedIpAddress: String?,
    persistedCountryCode: String?,
    persistedIpAddress: String?,
): ServerExitInfo {
    val country = if (isSelected) {
        liveCountryCode?.takeIf(String::isNotBlank)
            ?: cachedCountryCode?.takeIf(String::isNotBlank)
            ?: persistedCountryCode?.takeIf(String::isNotBlank)
    } else {
        cachedCountryCode?.takeIf(String::isNotBlank)
            ?: persistedCountryCode?.takeIf(String::isNotBlank)
    }
    val ip = if (isSelected) {
        liveIpAddress?.trim()?.takeIf(String::isNotEmpty)
            ?: cachedIpAddress?.trim()?.takeIf(String::isNotEmpty)
            ?: persistedIpAddress?.trim()?.takeIf(String::isNotEmpty)
    } else {
        cachedIpAddress?.trim()?.takeIf(String::isNotEmpty)
            ?: persistedIpAddress?.trim()?.takeIf(String::isNotEmpty)
    }
    return ServerExitInfo(country, ip)
}

/** Keeps the latest verified egress in the in-memory server rows between status updates. */
internal fun applyServerExitInfo(
    servers: List<ServersCache>,
    guid: String,
    countryCode: String?,
    ipAddress: String?,
): List<ServersCache> {
    if (guid.isBlank()) return servers
    val country = countryCode?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
    val ip = ipAddress?.trim()?.takeIf(String::isNotEmpty)
    if (country == null && ip == null) return servers

    var changed = false
    val updated = servers.map { server ->
        if (server.guid != guid) return@map server
        val nextCountry = country ?: server.profile.lastExitCountryCode
        val nextIp = ip ?: server.profile.lastExitIpAddress
        if (nextCountry == server.profile.lastExitCountryCode && nextIp == server.profile.lastExitIpAddress) {
            return@map server
        }
        changed = true
        server.copy(
            profile = server.profile.copy(
                lastExitCountryCode = nextCountry,
                lastExitIpAddress = nextIp,
            ),
        )
    }
    return if (changed) updated else servers
}

internal fun GroupMapItem.withDefaultTrafficUsage(
    usedBytes: Long,
    limitBytes: Long,
    periodDays: Int,
): GroupMapItem =
    if (isDefaultSubscriptionGroup(id)) {
        copy(
            trafficTotalBytes = limitBytes.coerceAtLeast(0L),
            trafficUsedBytes = usedBytes.coerceAtLeast(0L),
            trafficPeriodDays = periodDays.coerceIn(0, 3650),
        )
    } else {
        this
    }

/** Applies the locally tracked graph values to Cloudflare Worker/Pages and bundled ServerLess groups. */
internal fun GroupMapItem.withLocalSubscriptionTrafficUsage(
    usedBytes: Long,
    limitBytes: Long,
    periodDays: Int,
): GroupMapItem =
    if (usesLocalTrafficGraph) {
        copy(
            trafficTotalBytes = limitBytes.coerceAtLeast(0L),
            trafficUsedBytes = usedBytes.coerceAtLeast(0L),
            trafficPeriodDays = periodDays.coerceIn(0, 3650),
        )
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

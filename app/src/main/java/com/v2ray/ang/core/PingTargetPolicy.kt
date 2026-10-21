package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** MasterDNS profiles own a local bridge that must never be run by delay-test workers. */
internal fun isProfilePingable(profile: ProfileItem?): Boolean =
    profile == null || (!MasterDnsBridge.isProfile(profile) && profile.configType != EConfigType.OPENVPN)

/** OpenVPN can display a delay from a standalone tunnel probe. */
internal fun canDisplayProfilePing(profile: ProfileItem?): Boolean =
    isProfilePingable(profile) || profile?.configType == EConfigType.OPENVPN

/** A live selected OpenVPN profile can be measured through its established Android VPN route. */
internal fun canProbeOpenVpnProfile(
    guid: String,
    selectedGuid: String?,
    isTunnelConnected: Boolean,
): Boolean = guid == selectedGuid && isTunnelConnected

/** Manual connection checks use the selected DNS bridge's SOCKS adapter. */
internal fun dnsTunnelConnectionProbePort(profile: ProfileItem?): Int? =
    profile?.takeIf(MasterDnsBridge::isProfile)?.let(MasterDnsBridge::outboundPort)

/** Retains missing profiles so the worker can report their existing invalid-config result. */
internal fun filterPingableGuids(
    guids: List<String>,
    includeOpenVpnProfiles: Boolean = false,
    profileForGuid: (String) -> ProfileItem?,
): List<String> = guids.filter { guid ->
    val profile = profileForGuid(guid)
    isProfilePingable(profile) ||
        (profile?.configType == EConfigType.OPENVPN && includeOpenVpnProfiles)
}

internal fun isInvalidPingResult(profile: ProfileItem?, delayMillis: Long): Boolean =
    delayMillis < 0L && isProfilePingable(profile)

package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty

private val joinedIpv4AddressPattern = Regex(
    """(?<![A-Za-z0-9_.])(?:\d{1,3}\.){3}\d{1,3}(?:\+(?:\d{1,3}\.){3}\d{1,3})+(?![A-Za-z0-9_.])""",
)

/**
 * Xray's native parser expects a single host/IP in endpoint fields. A few imported profiles
 * carry alternative IPv4 endpoints joined with `+`; that value reaches Go's netip.ParseAddr
 * and panics the native daemon instead of returning a failed ping.
 */
internal fun containsUnsupportedJoinedIpv4Address(configJson: String): Boolean =
    joinedIpv4AddressPattern.containsMatchIn(configJson)

/** DNS-tunnel profiles use their own local bridge instead of a generic server TCP probe. */
internal fun isProfilePingable(profile: ProfileItem?): Boolean =
    profile == null || (profile.configType == EConfigType.SOCKS && SoarBridge.isProfile(profile)) ||
        (!MasterDnsBridge.isProfile(profile) && !SoarBridge.isProfile(profile) && profile.configType != EConfigType.OPENVPN)

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
    profile?.let {
        when {
            SoarBridge.isProfile(it) -> SoarBridge.outboundPort(it)
            MasterDnsBridge.isProfile(it) -> MasterDnsBridge.outboundPort(it)
            else -> null
        }
    }

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

/** The v2rayNG 2.3.12 fast TCP reachability check for ordinary proxy profiles. */
internal fun shouldUseV2rayNgTcpPreflight(profile: ProfileItem): Boolean =
    isProfilePingable(profile) &&
        profile.configType != EConfigType.OPENVPN &&
        profile.configType != EConfigType.WARP &&
        profile.configType != EConfigType.AMNEZIAWG &&
        !SoarBridge.isProfile(profile) &&
        !profile.psiphonEnabled &&
        !profile.torEnabled &&
        !profile.bepassEnabled &&
        !profile.configType.isComplexType() &&
        profile.configType != EConfigType.HYSTERIA2 &&
        profile.configType != EConfigType.WIREGUARD &&
        profile.alpn?.startsWith("h3") != true &&
        profile.server.isNotNullEmpty() &&
        profile.serverPort?.toIntOrNull() != null

/** PingNG-owned routes need their live bridge probes; ordinary profiles use the upstream path. */
internal fun shouldUseV2rayNgRealDelayProbe(profile: ProfileItem): Boolean =
    !MasterDnsBridge.isProfile(profile) &&
        !SoarBridge.isProfile(profile) &&
        profile.configType != EConfigType.WARP &&
        profile.configType != EConfigType.AMNEZIAWG &&
        profile.configType != EConfigType.OPENVPN &&
        !profile.psiphonEnabled &&
        !profile.torEnabled &&
        !profile.bepassEnabled &&
        !PingNgCompat.isNativeDesyncEnabled(profile)

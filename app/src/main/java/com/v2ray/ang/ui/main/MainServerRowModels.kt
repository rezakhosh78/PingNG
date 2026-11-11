package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.core.MasterDnsBridge
import com.v2ray.ang.core.SoarBridge
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager

internal data class ServerRowUiModel(
    val guid: String,
    val profile: ProfileItem,
    val remarks: String,
    val statistics: String,
    val typeDescription: String,
    val testDelayMillis: Long,
    val subscriptionBadge: String,
)

internal data class ServerGroupUiState(
    val servers: List<ServersCache> = emptyList(),
    val rows: List<ServerRowUiModel> = emptyList(),
)

internal fun buildServerRowUiModel(
    server: ServersCache,
    subscriptionRemarks: String,
): ServerRowUiModel {
    val profile = server.profile
    return ServerRowUiModel(
        guid = server.guid,
        profile = profile,
        remarks = profile.remarks,
        statistics = serverStatistics(profile),
        typeDescription = serverProtocolDescription(profile),
        testDelayMillis = server.testDelayMillis,
        subscriptionBadge = subscriptionRemarks.firstOrNull()?.toString().orEmpty(),
    )
}

private fun serverProtocolDescription(profile: ProfileItem): String {
    if (SoarBridge.isProfile(profile)) return "SOAR DNS"
    if (MasterDnsBridge.isProfile(profile)) {
        return when (profile.description) {
            MasterDnsBridge.DNSTT ->
                if (profile.dnsTunnelMode.equals("SSH", ignoreCase = true)) "DNSTT SSH" else "DNSTT SOCKS5"
            else -> MasterDnsBridge.UI_LABEL
        }
    }
    val configTypeName = if (profile.configType == com.v2ray.ang.enums.EConfigType.OPENVPN) "OpenVPN" else profile.configType.name
    if (profile.configType.isComplexType()) return configTypeName
    val parts = mutableListOf(configTypeName)
    profile.network?.let { network ->
        if (network.isNotBlank() && !network.equals("tcp", ignoreCase = true)) {
            parts.add(network)
        }
    }
    profile.security?.let { security ->
        if (security.isNotBlank()) {
            parts.add(
                if (profile.insecure == true && security.equals("tls", ignoreCase = true)) {
                    "$security insecure"
                } else {
                    security
                }
            )
        }
    }
    return parts.joinToString(" / ")
}

private fun serverStatistics(profile: ProfileItem): String = when {
    SoarBridge.isProfile(profile) -> profile.soarZone?.trim()?.takeIf(String::isNotEmpty) ?: "SOAR DNS"
    MasterDnsBridge.isProfile(profile) -> profile.masterDnsDomain?.trim()?.takeIf(String::isNotEmpty)
        ?: if (profile.description == MasterDnsBridge.DNSTT) "DNSTT" else MasterDnsBridge.UI_LABEL
    else -> profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
}

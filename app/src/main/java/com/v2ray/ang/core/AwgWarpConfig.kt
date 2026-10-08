package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Defaults and marker for a generated Cloudflare WARP profile over AmneziaWG. */
object AwgWarpConfig {
    const val DESCRIPTION = "PINGNG_AWG_WARP"
    const val DEFAULT_REMARK = "WARP AWG"
    const val DEFAULT_ENDPOINT = "8.6.112.31:500"
    const val DEFAULT_AUTO_SCAN_ENDPOINT = true
    const val ENDPOINT_SCAN_VERSION = "0.16.0"
    const val JUNK_PACKET_COUNT = 5
    const val JUNK_PACKET_MIN = 10
    const val JUNK_PACKET_MAX = 40
    const val DEFAULT_DNS = "1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001"

    fun isProfile(profile: ProfileItem?): Boolean =
        profile?.configType == EConfigType.AMNEZIAWG && profile.description == DESCRIPTION

    fun render(account: WarpAccount, endpoint: String = DEFAULT_ENDPOINT): String = buildString {
        appendLine("# Generated AmneziaWG WARP Config")
        appendLine("[Interface]")
        appendLine("PrivateKey = ${account.privateKey}")
        appendLine("Address = ${account.localAddress.split(',').joinToString(", ") { it.trim() }}")
        appendLine("DNS = $DEFAULT_DNS")
        appendLine("MTU = ${account.mtu}")
        appendLine("Jc = $JUNK_PACKET_COUNT")
        appendLine("Jmin = $JUNK_PACKET_MIN")
        appendLine("Jmax = $JUNK_PACKET_MAX")
        appendLine("S1 = 0")
        appendLine("S2 = 0")
        appendLine("H1 = 1")
        appendLine("H2 = 2")
        appendLine("H3 = 3")
        appendLine("H4 = 4")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${account.peerPublicKey}")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        append("Endpoint = $endpoint")
    }
}

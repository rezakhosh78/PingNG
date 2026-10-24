package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.enums.EConfigType

/** Routing rules for Psiphon TCP and first-hop UDP fallback. */
internal object PsiphonRoutingPolicy {
    private const val DNS_OUTBOUND_TAG = "dns-out"

    /** TCP-only DNS tunnels cannot carry the UDP fallback used by other first hops. */
    fun udpFallbackOutbound(
        tcpOnlyDnsTunnel: Boolean,
        firstHopOutboundTag: String,
        blockedOutboundTag: String,
    ): String = if (tcpOnlyDnsTunnel) blockedOutboundTag else firstHopOutboundTag

    /** Probe independent endpoints in order without overloading a reconnecting SOCKS tunnel. */
    fun liveEgressProbeUrls(vararg candidates: String?): List<String> = candidates
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()

    /** Ensures port-53 packets can enter Xray's configured DNS module in Psiphon mode. */
    fun ensureDnsOutbound(outbounds: JsonArray): String {
        val existingDns = outbounds.firstOrNull { outbound ->
            if (!outbound.isJsonObject) return@firstOrNull false
            val item = outbound.asJsonObject
            item.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString
                ?.equals("dns", ignoreCase = true) == true &&
                item.get("tag")?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank() == true
        }
        if (existingDns != null) {
            return existingDns.asJsonObject.get("tag").asString
        }

        var tag = DNS_OUTBOUND_TAG
        var suffix = 1
        while (outbounds.any { outbound ->
                outbound.isJsonObject && outbound.asJsonObject.get("tag")
                    ?.takeIf { it.isJsonPrimitive }?.asString == tag
            }
        ) {
            tag = "pingng-psiphon-dns-out-${suffix++}"
        }
        outbounds.add(JsonObject().apply {
            addProperty("tag", tag)
            addProperty("protocol", "dns")
        })
        return tag
    }

    /** DNS uses Xray's DNS module; all other UDP uses the first-hop outbound. */
    fun userUdpRules(
        dnsOutboundTag: String?,
        udpOutboundTag: String,
    ): List<JsonObject> = buildList {
        if (!dnsOutboundTag.isNullOrBlank()) {
            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "udp")
                addProperty("port", "53")
                addProperty("outboundTag", dnsOutboundTag)
            })
        }
        add(JsonObject().apply {
            addProperty("type", "field")
            addProperty("network", "udp")
            addProperty("outboundTag", udpOutboundTag)
        })
    }
}

/** Uses the same SOCKS listener for health checks that the Android VPN sends user traffic to. */
internal object PsiphonProbeRoutePolicy {
    /** The AWG gateway's TCP leg exits through Psiphon; Cloudflare confirms that same egress. */
    fun usesPsiphonFinalEgress(
        configType: EConfigType?,
        psiphonEnabled: Boolean,
        torEnabled: Boolean,
    ): Boolean = configType == EConfigType.AMNEZIAWG && psiphonEnabled && !torEnabled

    fun shouldPreferCloudflareExitIp(
        configType: EConfigType?,
        psiphonEnabled: Boolean,
        torEnabled: Boolean,
    ): Boolean = usesPsiphonFinalEgress(configType, psiphonEnabled, torEnabled)

    fun waitForAmneziaGateway(
        configType: EConfigType?,
        psiphonEnabled: Boolean,
        torEnabled: Boolean,
        gatewayPort: Int,
    ): Boolean = configType == EConfigType.AMNEZIAWG &&
        (psiphonEnabled || torEnabled) && gatewayPort !in 1..65535

    fun socksPort(
        configType: EConfigType?,
        psiphonEnabled: Boolean,
        torEnabled: Boolean,
        amneziaGatewayPort: Int,
        psiphonPort: Int,
        torPort: Int,
    ): Int? {
        if (!psiphonEnabled && !torEnabled) return null
        val port = if (configType == EConfigType.AMNEZIAWG) {
            amneziaGatewayPort
        } else if (torEnabled) {
            torPort
        } else {
            psiphonPort
        }
        return port.takeIf { it in 1..65535 }
    }
}

/** IP-route defaults for Psiphon chained over AWG; VPN app filters still choose the apps routed. */
internal object PsiphonDeviceRoutingPolicy {
    data class RouteOptions(
        val excludePingNg: Boolean,
        val bypassLan: Boolean,
        val ipv6Enabled: Boolean,
    )

    fun isFullDeviceAwgPsiphon(
        configType: EConfigType?,
        psiphonEnabled: Boolean,
    ): Boolean = configType == EConfigType.AMNEZIAWG && psiphonEnabled

    fun routeOptions(
        configType: EConfigType?,
        psiphonEnabled: Boolean,
        requestedBypassLan: Boolean,
        requestedIpv6Enabled: Boolean,
    ): RouteOptions {
        val fullDevice = isFullDeviceAwgPsiphon(configType, psiphonEnabled)
        return RouteOptions(
            excludePingNg = fullDevice,
            bypassLan = requestedBypassLan && !fullDevice,
            ipv6Enabled = requestedIpv6Enabled || fullDevice,
        )
    }
}

/** Prevents Psiphon's network-bound runtime from opening direct CDN egress over the physical link. */
internal object PsiphonRuntimeRoutePolicy {
    fun shouldUseDirectCdnBootstrap(mode: String, forceLocalUpstream: Boolean): Boolean =
        mode.equals("cdn", ignoreCase = true) && !forceLocalUpstream

    fun upstreamUrl(
        forceLocalUpstream: Boolean,
        configuredProxyUrl: String?,
        localGatewayUrl: String,
    ): String = if (forceLocalUpstream) localGatewayUrl else configuredProxyUrl ?: localGatewayUrl
}

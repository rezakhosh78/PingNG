package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.enums.EConfigType

/** Routing rules for Psiphon TCP and first-hop UDP fallback. */
internal object PsiphonRoutingPolicy {
    private const val DNS_OUTBOUND_TAG = "dns-out"

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

package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil

/** User-editable Bepass options. The local listener address is always supplied by PingNG. */
data class BepassSettings(
    val tlsHeaderLength: Int = 5,
    val tlsPaddingEnabled: Boolean = false,
    val tlsPaddingMin: Int = 40,
    val tlsPaddingMax: Int = 80,
    val remoteDnsAddress: String = "https://1.1.1.1/dns-query",
    val dnsFragmentationEnabled: Boolean = false,
    val dnsCacheTtlSeconds: Int = 1800,
    val dnsRequestTimeoutSeconds: Int = 10,
    val chunksBeforeSniMin: Int = 2000,
    val chunksBeforeSniMax: Int = 2000,
    val sniChunkMin: Int = 5,
    val sniChunkMax: Int = 10,
    val chunksAfterSniMin: Int = 2000,
    val chunksAfterSniMax: Int = 2000,
    val delayBetweenChunksMin: Int = 10,
    val delayBetweenChunksMax: Int = 20,
    val workerAddress: String = "",
    val workerIpPortAddress: String = "104.17.196.93:2096",
    val workerEnabled: Boolean = false,
    val workerDnsOnly: Boolean = false,
    val lowLevelSocketsEnabled: Boolean = false,
    /** Comma-separated host=IP pairs. */
    val hosts: String = "",
    val udpReadTimeoutSeconds: Int = 120,
    val udpWriteTimeoutSeconds: Int = 120,
    val udpLinkIdleTimeoutSeconds: Long = 120,
) {
    fun validate(): String? {
        if (tlsHeaderLength !in 0..64) return "tlsHeaderLength"
        if (!validRange(tlsPaddingMin, tlsPaddingMax, 0..65535)) return "tlsPaddingSize"
        if (!validRange(chunksBeforeSniMin, chunksBeforeSniMax, 0..65535)) return "chunksLengthBeforeSni"
        if (!validRange(sniChunkMin, sniChunkMax, 1..65535)) return "sniChunksLength"
        if (!validRange(chunksAfterSniMin, chunksAfterSniMax, 0..65535)) return "chunksLengthAfterSni"
        if (!validRange(delayBetweenChunksMin, delayBetweenChunksMax, 0..5000)) return "delayBetweenChunks"
        if (dnsCacheTtlSeconds !in 1..31_536_000) return "dnsCacheTtlSeconds"
        if (dnsRequestTimeoutSeconds !in 1..120) return "dnsRequestTimeoutSeconds"
        if (udpReadTimeoutSeconds !in 1..3600) return "udpReadTimeoutSeconds"
        if (udpWriteTimeoutSeconds !in 1..3600) return "udpWriteTimeoutSeconds"
        if (udpLinkIdleTimeoutSeconds !in 1L..3600L) return "udpLinkIdleTimeoutSeconds"
        if (workerEnabled) {
            val workerUri = runCatching { java.net.URI(workerAddress.trim()) }.getOrNull()
            if (workerUri?.scheme !in setOf("https", "http") || workerUri?.host.isNullOrBlank()) {
                return "workerAddress"
            }
            if (!workerDnsOnly && !isHostPort(workerIpPortAddress)) return "workerIpPortAddress"
        }
        val resolver = remoteDnsAddress.trim()
        if (resolver.isBlank()) return "remoteDnsAddress"
        if (resolver.startsWith("https://", ignoreCase = true)) {
            val resolverUri = runCatching { java.net.URI(resolver) }.getOrNull()
            if (resolverUri?.host.isNullOrBlank()) return "remoteDnsAddress"
        } else if (!isHostPort(resolver)) {
            return "remoteDnsAddress"
        }
        if (parseHosts(hosts).second) return "hosts"
        return null
    }

    fun toJson(): String = JsonUtil.toJson(this)

    /** Builds Bepass's native config while keeping both proxy listeners private. */
    fun toRuntimeJson(socksPort: Int): String = JsonObject().apply {
        addProperty("TLSHeaderLength", tlsHeaderLength)
        addProperty("TLSPaddingEnabled", tlsPaddingEnabled)
        add("TLSPaddingSize", rangeArray(tlsPaddingMin, tlsPaddingMax))
        addProperty("RemoteDNSAddr", remoteDnsAddress.trim())
        addProperty("EnableDNSFragmentation", dnsFragmentationEnabled)
        addProperty("DnsCacheTTL", dnsCacheTtlSeconds)
        addProperty("DnsRequestTimeout", dnsRequestTimeoutSeconds)
        addProperty("BindAddress", "127.0.0.1:$socksPort")
        add("ChunksLengthBeforeSni", rangeArray(chunksBeforeSniMin, chunksBeforeSniMax))
        add("SniChunksLength", rangeArray(sniChunkMin, sniChunkMax))
        add("ChunksLengthAfterSni", rangeArray(chunksAfterSniMin, chunksAfterSniMax))
        add("DelayBetweenChunks", rangeArray(delayBetweenChunksMin, delayBetweenChunksMax))
        addProperty("WorkerAddress", workerAddress.trim())
        addProperty("WorkerIPPortAddress", workerIpPortAddress.trim())
        addProperty("WorkerEnabled", workerEnabled)
        addProperty("WorkerDNSOnly", workerDnsOnly)
        // The Android runtime is bound to the underlying Network by its service.
        // Bepass's own protect_path Unix socket is not present in this app.
        addProperty("EnableLowLevelSockets", false)
        add("Hosts", hostsJson())
        // This profile uses the SOCKS TCP path. Keep the unused UDP listener local.
        addProperty("UDPBindAddress", "127.0.0.1")
        addProperty("UDPReadTimeout", udpReadTimeoutSeconds)
        addProperty("UDPWriteTimeout", udpWriteTimeoutSeconds)
        addProperty("UDPLinkIdleTimeout", udpLinkIdleTimeoutSeconds)
    }.toString()

    private fun hostsJson(): JsonArray {
        val (entries, _) = parseHosts(hosts)
        return JsonArray().apply {
            entries.forEach { (domain, ip) ->
                add(JsonObject().apply {
                    addProperty("Domain", domain)
                    addProperty("IP", ip)
                })
            }
        }
    }

    companion object {
        val TCP_NETWORKS = setOf("tcp", "ws", "httpupgrade", "h2", "grpc", "xhttp")
        val TLS_SECURITY = setOf("tls", "reality")
        val COMPATIBLE_PROTOCOLS = setOf(
            EConfigType.VMESS,
            EConfigType.VLESS,
            EConfigType.TROJAN,
            EConfigType.SHADOWSOCKS,
        )

        fun fromJson(raw: String?): BepassSettings {
            if (raw.isNullOrBlank()) return BepassSettings()
            return runCatching {
                val merged = JsonParser.parseString(JsonUtil.toJson(BepassSettings())).asJsonObject
                JsonParser.parseString(raw).asJsonObject.entrySet().forEach { (key, value) ->
                    merged.add(key, value)
                }
                JsonUtil.fromJsonSafe(merged.toString(), BepassSettings::class.java)
            }.getOrNull() ?: BepassSettings()
        }

        fun isSupported(profile: ProfileItem): Boolean = isSupported(
            profile.configType,
            profile.network.orEmpty(),
            profile.security.orEmpty(),
        )

        fun hasTcpFinalMask(profile: ProfileItem): Boolean {
            val tcpMasks = JsonUtil.parseString(profile.finalMask)?.get("tcp")
            return tcpMasks?.isJsonArray == true && tcpMasks.asJsonArray.size() > 0
        }

        fun isSupported(configType: EConfigType, network: String, security: String): Boolean =
            configType in COMPATIBLE_PROTOCOLS &&
                network.trim().lowercase() in TCP_NETWORKS &&
                security.trim().lowercase() in TLS_SECURITY

        private fun validRange(first: Int, second: Int, allowed: IntRange): Boolean =
            first in allowed && second in allowed && first <= second

        private fun rangeArray(first: Int, second: Int) = JsonArray().apply {
            add(first)
            add(second)
        }

        private fun isHostPort(value: String): Boolean = runCatching {
            val uri = java.net.URI("tcp://${value.trim()}")
            !uri.host.isNullOrBlank() && uri.port in 1..65535
        }.getOrDefault(false)

        private fun parseHosts(value: String): Pair<List<Pair<String, String>>, Boolean> {
            val entries = mutableListOf<Pair<String, String>>()
            val lines = value.split(',', '\n').map(String::trim).filter(String::isNotBlank)
            for (line in lines) {
                val separator = line.indexOf('=')
                if (separator <= 0 || separator == line.lastIndex) return emptyList<Pair<String, String>>() to true
                val domain = line.substring(0, separator).trim()
                val ip = line.substring(separator + 1).trim()
                if (domain.isBlank() || ip.isBlank()) return emptyList<Pair<String, String>>() to true
                entries += domain to ip
            }
            return entries to false
        }
    }
}

/** Applies Bepass as a dialer proxy only to a TLS-over-TCP primary outbound. */
object BepassConfigPolicy {
    const val SOCKS_OUTBOUND_TAG = "pingng-bepass-socks"

    fun attach(
        config: JsonObject,
        profile: ProfileItem,
        socksPort: Int,
    ): Boolean {
        if (!profile.bepassEnabled) return false
        check(BepassSettings.isSupported(profile)) { "Bepass is not supported by this profile transport" }
        check(!BepassSettings.hasTcpFinalMask(profile)) {
            "Bepass cannot be combined with a TCP FinalMask on this profile"
        }
        check(socksPort in 1..65535) { "Bepass SOCKS listener is unavailable" }

        val outbounds = config.getAsJsonArray("outbounds")
            ?: error("The profile's primary outbound was not generated")
        val outbound = outbounds.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString == "proxy"
        }?.asJsonObject ?: error("The profile's primary outbound was not generated")
        check(outbound.get("protocol")?.asString?.lowercase() in setOf("vmess", "vless", "trojan", "shadowsocks")) {
            "Bepass requires a TLS-over-TCP proxy outbound"
        }
        val stream = outbound.getAsJsonObject("streamSettings")
            ?: error("The profile does not have a TCP stream transport")
        val sockopt = stream.getAsJsonObject("sockopt") ?: JsonObject().also { stream.add("sockopt", it) }
        val existingDialer = sockopt.get("dialerProxy")?.takeIf { it.isJsonPrimitive }?.asString
        check(existingDialer.isNullOrBlank() || existingDialer == SOCKS_OUTBOUND_TAG) {
            "Bepass cannot replace another dialer proxy chain"
        }
        sockopt.addProperty("dialerProxy", SOCKS_OUTBOUND_TAG)

        // CoreConfigManager may add its automatic TCP fragment mask before
        // Bepass is attached. Bepass owns TLS ClientHello fragmentation for
        // this connection, so remove only that automatic TCP layer and keep
        // any separate UDP masks intact.
        if (profile.finalMask.isNullOrBlank()) {
            stream.getAsJsonObject("finalmask")?.let { masks ->
                masks.remove("tcp")
                if (masks.size() == 0) stream.remove("finalmask")
            }
        }

        outbounds.toList().filter {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString == SOCKS_OUTBOUND_TAG
        }.forEach { outbounds.remove(it) }
        outbounds.add(JsonObject().apply {
            addProperty("tag", SOCKS_OUTBOUND_TAG)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("version", "5")
                addProperty("udp", false)
                add("servers", JsonArray().also { servers ->
                    servers.add(JsonObject().apply {
                        addProperty("address", "127.0.0.1")
                        addProperty("port", socksPort)
                    })
                })
            })
            add("streamSettings", JsonObject().apply {
                addProperty("network", "tcp")
                add("sockopt", JsonObject().apply { addProperty("domainStrategy", "UseIPv4") })
            })
        })
        return true
    }
}

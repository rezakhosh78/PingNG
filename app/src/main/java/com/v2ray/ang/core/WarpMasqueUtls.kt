package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import java.net.Inet6Address
import java.net.InetAddress

/** uTLS modes for WARP MASQUE/H2. Empty keeps the existing usque core unchanged. */
object WarpMasqueUtls {
    const val LEGACY = ""

    val fingerprints = listOf(
        "chrome",
        "safari",
        "ios",
        "android",
        "edge",
        "360",
        "qq",
        "random",
        "randomized",
    )

    fun normalize(value: String?): String = value?.trim()?.lowercase()
        ?.takeIf { it in fingerprints }
        ?: LEGACY

    /** Use Xray's supported alias so its uTLS version matches the bundled core. */
    fun xrayFingerprint(value: String?): String = normalize(value)

    fun isEnabled(profile: ProfileItem): Boolean = normalize(profile.warpMasqueUtlsFingerprint).isNotEmpty()

    fun requireCompatible(profile: ProfileItem) {
        if (!isEnabled(profile)) return
        require(profile.warpMasqueHttp2Enabled != false) {
            "WARP MASQUE uTLS requires HTTP/2. Enable HTTP/2 or choose Go TLS (current)."
        }
        require(!profile.psiphonEnabled && !profile.torEnabled) {
            "WARP MASQUE uTLS currently requires a direct profile. Turn off Psiphon/Tor or choose Go TLS (current)."
        }
    }

    /** Build the exact Xray MASQUE WarpObject from the registered usque account. */
    fun buildWarpSettings(profile: ProfileItem): JsonObject {
        val raw = profile.warpMasqueConfigJson?.takeIf(String::isNotBlank)
            ?: error("WARP MASQUE registration is missing; connect once with Go TLS (current) first")
        val root = JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
            ?: error("WARP MASQUE registration JSON is invalid")

        fun text(obj: JsonObject, key: String): String =
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()

        val privateKey = text(root, "private_key")
        val endpointPublicKey = listOf("endpoint_pub_key", "peer_public_key")
            .firstNotNullOfOrNull { key -> text(root, key).takeIf(String::isNotBlank) }
            .orEmpty()
        val addresses = root.get("addresses")?.takeIf { it.isJsonObject }?.asJsonObject
        val ipv4 = text(root, "ipv4").ifBlank { addresses?.let { text(it, "v4") }.orEmpty() }
        val ipv6 = text(root, "ipv6").ifBlank { addresses?.let { text(it, "v6") }.orEmpty() }
        check(privateKey.isNotBlank() && endpointPublicKey.isNotBlank() && ipv4.isNotBlank()) {
            "WARP MASQUE registration is missing its device key, endpoint key, or IPv4 address"
        }

        val warp = JsonObject().apply {
            addProperty("privateKey", privateKey)
            addProperty("publicKey", endpointPublicKey)
            add("address", com.google.gson.JsonArray().apply {
                add(withPrefix(ipv4, "/32"))
                if (ipv6.isNotBlank()) add(withPrefix(ipv6, "/128"))
            })
        }
        return JsonObject().apply { add("warp", warp) }
    }

    fun endpoint(profile: ProfileItem): WarpMasqueConfig.EditorEndpoint =
        WarpMasqueConfig.editorEndpoint(profile)

    fun remoteDnsServers(profile: ProfileItem): List<String> =
        parseRemoteDnsServers(profile.warpMasqueDns)

    fun parseRemoteDnsServers(raw: String?): List<String> {
        val servers = raw.orEmpty()
            .split(',').map { it.trim().removeSurrounding("[", "]") }
            .filter { it.isNotEmpty() }
            .ifEmpty { WarpMasqueConfig.DEFAULT_DNS.split(',') }
        require(servers.all(::isIpLiteral)) {
            "WARP MASQUE uTLS DNS servers must be IPv4 or IPv6 addresses"
        }
        return servers
    }

    fun valueForLabel(label: String, legacyLabel: String): String = when {
        label == legacyLabel -> LEGACY
        else -> fingerprints.firstOrNull { labelFor(it, legacyLabel).equals(label, ignoreCase = true) } ?: LEGACY
    }

    fun labelFor(value: String?, legacyLabel: String): String {
        val normalized = normalize(value)
        return when (normalized) {
            "ios" -> "iOS"
            "qq" -> "QQ"
            "360" -> "360"
            else -> normalized.replaceFirstChar { it.uppercase() }.ifBlank { legacyLabel }
        }
    }

    private fun withPrefix(value: String, suffix: String): String =
        if ('/' in value) value else value + suffix

    private fun isIpLiteral(value: String): Boolean {
        if (':' in value) return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}

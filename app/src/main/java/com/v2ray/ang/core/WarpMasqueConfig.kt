package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.AmneziaWgFmt

/** Stable metadata for the standalone WARP MASQUE profile. */
object WarpMasqueConfig {
    data class EditorEndpoint(val host: String, val port: Int)

    const val DESCRIPTION = "WARP MASQUE/H2"
    /** The first MASQUE endpoint tried on every fresh connection. */
    const val PRIMARY_ENDPOINT = "162.159.198.238:443"
    const val DEFAULT_ENDPOINTS = "162.159.198.0/24:443,162.159.199.0/24:443"
    const val LEGACY_DEFAULT_ENDPOINTS = "162.159.198.0/24:443"
    const val DEFAULT_ENDPOINT = "162.159.198.238"
    const val DEFAULT_PORT = 443
    const val DEFAULT_SNI = "cloudflare.com"
    const val ENDPOINT_MODE_AUTO = "Auto"
    const val ENDPOINT_MODE_CUSTOM = "Custom"
    const val DEFAULT_SOCKS_BIND = "127.0.0.1"
    const val DEFAULT_SOCKS_PORT = 1819
    const val DEFAULT_DEVICE_NAME = "PingNG WARP MASQUE"
    const val DEFAULT_DNS = "1.1.1.1,1.0.0.1"

    private val DEVICE_WORDS = listOf(
        "Falcon", "Orbit", "Nova", "Pixel", "River", "Comet", "Echo", "Atlas",
        "Mosaic", "Nimbus", "Cedar", "Aurora",
    )

    fun randomDeviceName(): String =
        "PingNG-${DEVICE_WORDS.shuffled().take(2).joinToString("-")}"

    fun normalizeSni(value: String?): String {
        val normalized = value?.trim().orEmpty()
        // Only an empty value means "use the app default". Older built-in
        // defaults such as hcaptcha.com can also be deliberate user choices.
        return normalized.ifBlank { DEFAULT_SNI }
    }

    /** Treat old custom profiles as manual until the new switch is explicitly saved. */
    fun shouldAutoScan(profile: ProfileItem): Boolean = profile.autoScanEndpoint ?: when {
        profile.warpMasqueEndpointMode == ENDPOINT_MODE_CUSTOM -> false
        profile.warpMasqueEndpointMode == ENDPOINT_MODE_AUTO -> true
        profile.warpMasquePrimaryEndpoint?.trim()?.let {
            it.isNotBlank() && it != DEFAULT_ENDPOINT && it != PRIMARY_ENDPOINT
        } == true -> false
        else -> true
    }

    /**
     * Returns the endpoint that the editor should display. An automatic scan
     * persists its result separately from the configured primary endpoint, so
     * prefer that result when reopening the editor. For manual profiles, an
     * explicit custom primary remains authoritative.
     */
    fun editorEndpoint(profile: ProfileItem): EditorEndpoint {
        val selected = parseSelectedEndpoint(profile.warpMasqueSelectedEndpoint)
        val configured = parseHostAndPort(
            profile.warpMasquePrimaryEndpoint,
            profile.warpMasqueEndpointPort ?: DEFAULT_PORT,
        )
        val runtime = parseHostAndPort(profile.server, profile.serverPort?.toIntOrNull())
        val default = EditorEndpoint(DEFAULT_ENDPOINT, DEFAULT_PORT)
        val configuredCustom = configured?.takeUnless { it == default }

        return if (shouldAutoScan(profile)) {
            selected ?: configuredCustom ?: runtime ?: configured ?: default
        } else {
            configuredCustom ?: selected ?: runtime ?: configured ?: default
        }
    }

    private fun parseSelectedEndpoint(value: String?): EditorEndpoint? {
        val (host, portText) = AmneziaWgFmt.splitEndpoint(value.orEmpty())
        val normalizedHost = host?.trim()?.takeIf(String::isNotBlank) ?: return null
        val port = portText?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return EditorEndpoint(normalizedHost, port)
    }

    private fun parseHostAndPort(hostValue: String?, fallbackPort: Int?): EditorEndpoint? {
        val value = hostValue?.trim()?.takeIf(String::isNotBlank) ?: return null
        val hasEmbeddedPort = value.startsWith("[") || value.count { it == ':' } == 1
        val (parsedHost, parsedPort) = if (hasEmbeddedPort) {
            AmneziaWgFmt.splitEndpoint(value)
        } else {
            value to null
        }
        val host = parsedHost?.trim()?.removeSurrounding("[", "]")
            ?.takeIf(String::isNotBlank) ?: return null
        val port = parsedPort?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: fallbackPort?.takeIf { it in 1..65535 }
            ?: return null
        return EditorEndpoint(host, port)
    }

    fun isDescription(description: String?): Boolean =
        description?.startsWith("WARP MASQUE/H2") == true
}

package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem

/** Stable metadata for the standalone WARP MASQUE profile. */
object WarpMasqueConfig {
    const val DESCRIPTION = "WARP MASQUE/H2"
    /** The first MASQUE endpoint tried on every fresh connection. */
    const val PRIMARY_ENDPOINT = "162.159.198.238:443"
    const val DEFAULT_ENDPOINTS = "162.159.198.0/24:443,162.159.199.0/24:443"
    const val LEGACY_DEFAULT_ENDPOINTS = "162.159.198.0/24:443"
    const val DEFAULT_ENDPOINT = "162.159.198.238"
    const val DEFAULT_PORT = 443
    const val DEFAULT_SNI = "hcaptcha.com"
    const val PREVIOUS_DEFAULT_SNI = "soft98.ir"
    const val LEGACY_DEFAULT_SNI = "consumer-masque.cloudflareclient.com"
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

    fun normalizeSni(value: String?): String = value?.trim()
        ?.takeIf {
            it.isNotBlank() &&
                it != LEGACY_DEFAULT_SNI &&
                it != PREVIOUS_DEFAULT_SNI
        } ?: DEFAULT_SNI

    /** Treat old custom profiles as manual until the new switch is explicitly saved. */
    fun shouldAutoScan(profile: ProfileItem): Boolean = profile.autoScanEndpoint ?: when {
        profile.warpMasqueEndpointMode == ENDPOINT_MODE_CUSTOM -> false
        profile.warpMasqueEndpointMode == ENDPOINT_MODE_AUTO -> true
        profile.warpMasquePrimaryEndpoint?.trim()?.let {
            it.isNotBlank() && it != DEFAULT_ENDPOINT && it != PRIMARY_ENDPOINT
        } == true -> false
        else -> true
    }

    fun isDescription(description: String?): Boolean =
        description?.startsWith("WARP MASQUE/H2") == true
}

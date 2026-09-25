package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Marker and defaults for the single-hop WARP WireGuard profile. */
object WarpWireGuardConfig {
    const val DESCRIPTION = "PINGNG_WARP_WIREGUARD"
    const val ENDPOINT_MODE_FAST = "Fast"
    const val ENDPOINT_MODE_MEDIUM = "Medium"
    const val ENDPOINT_MODE_ALL = "All"
    const val ENDPOINT_MODE_CUSTOM = "Custom"
    const val DEFAULT_ENDPOINT = "188.114.96.206"
    const val DEFAULT_PORT = 878

    fun isDescription(value: String?): Boolean = value == DESCRIPTION

    /** Recognizes the marker and legacy profiles saved before it was preserved. */
    fun isProfile(profile: ProfileItem?): Boolean = profile != null && (
        isDescription(profile.description) ||
            (profile.configType == EConfigType.WIREGUARD && profile.warpEndpointTestMode != null)
        )

    fun normalizeMode(value: String?): String = when (value) {
        ENDPOINT_MODE_MEDIUM -> ENDPOINT_MODE_MEDIUM
        ENDPOINT_MODE_ALL -> ENDPOINT_MODE_ALL
        ENDPOINT_MODE_CUSTOM -> ENDPOINT_MODE_CUSTOM
        else -> ENDPOINT_MODE_FAST
    }
}

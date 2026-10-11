package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Marker and defaults for the single-hop WARP WireGuard profile. */
object WarpWireGuardConfig {
    const val DESCRIPTION = "PINGNG_WARP_WIREGUARD"
    const val ENDPOINT_MODE_AUTO = "Automatic"
    const val ENDPOINT_MODE_CUSTOM = "Custom"

    fun isDescription(value: String?): Boolean = value == DESCRIPTION

    /** Recognizes the marker and legacy profiles saved before it was preserved. */
    fun isProfile(profile: ProfileItem?): Boolean = profile != null && (
        isDescription(profile.description) ||
            (profile.configType == EConfigType.WIREGUARD && profile.warpEndpointTestMode != null)
        )

    fun normalizeMode(value: String?): String =
        if (value == ENDPOINT_MODE_CUSTOM) ENDPOINT_MODE_CUSTOM else ENDPOINT_MODE_AUTO
}

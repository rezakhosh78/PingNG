package com.v2ray.ang.core

/** Shared defaults and marker used by the WARP Plus (WARP-in-WARP) profile. */
object WarpPlusConfig {
    const val DESCRIPTION = "WARP Plus"
    const val LEGACY_DESCRIPTION = "WARP outer → WARP inner"

    const val ENDPOINT_MODE_AUTO = "Automatic"
    const val ENDPOINT_MODE_CUSTOM = "Custom"

    /** Keep profiles written by older builds readable after the UI rename. */
    fun normalizeEndpointTestMode(value: String?): String =
        if (value?.trim() == ENDPOINT_MODE_CUSTOM) ENDPOINT_MODE_CUSTOM else ENDPOINT_MODE_AUTO

    fun isDescription(value: String?): Boolean =
        value == DESCRIPTION || value == LEGACY_DESCRIPTION

    const val DEFAULT_KEEP_ALIVE = 5
    const val DEFAULT_FINAL_MASK = """{"udp":[{"type":"noise","settings":{"reset":"30-60","noise":[{"rand":"50-100","randRange":"16-255","delay":"1-3"},{"rand":"50-100","randRange":"16-255","delay":"1-3"},{"rand":"50-100","randRange":"16-255","delay":"1-3"}]}}]}"""
}

package com.v2ray.ang.core

/** Shared defaults and marker used by the WARP Plus (WARP-in-WARP) profile. */
object WarpPlusConfig {
    const val DESCRIPTION = "WARP Plus"
    const val LEGACY_DESCRIPTION = "WARP outer → WARP inner"

    const val ENDPOINT_MODE_FAST = "Fast"
    const val ENDPOINT_MODE_MEDIUM = "Medium"
    const val ENDPOINT_MODE_SLOW = "Slow"
    const val ENDPOINT_MODE_CUSTOM = "Custom"

    /** Keep profiles written by older builds readable after the UI rename. */
    fun normalizeEndpointTestMode(value: String?): String = when (value?.trim()) {
        "Slow: More Endpoints" -> ENDPOINT_MODE_SLOW
        "All Endpoints", "All Endpoint", ENDPOINT_MODE_MEDIUM -> ENDPOINT_MODE_MEDIUM
        // Profiles saved by builds that exposed Advance remain usable and are
        // intentionally migrated to the Medium search.
        "Advance" -> ENDPOINT_MODE_MEDIUM
        ENDPOINT_MODE_SLOW -> ENDPOINT_MODE_SLOW
        ENDPOINT_MODE_CUSTOM -> ENDPOINT_MODE_CUSTOM
        else -> ENDPOINT_MODE_FAST
    }

    fun isDescription(value: String?): Boolean =
        value == DESCRIPTION || value == LEGACY_DESCRIPTION

    const val DEFAULT_INNER_ENDPOINTS = "162.159.192.1:2408,188.114.99.119:891,engage.cloudflareclient.com:2408"
    const val DEFAULT_OUTER_ENDPOINTS = "engage.cloudflareclient.com:2408,188.114.98.27:890,162.159.192.1:2408"
    private val FAST_ENDPOINT_SEEDS = listOf(
        "188.114.97.6:7281", "188.114.97.6:859", "8.6.112.104:4233",
        "8.6.112.106:3138", "8.6.112.107:3138", "8.6.112.121:1180",
        "8.6.112.122:894", "8.6.112.127:4198", "8.6.112.133:968",
        "8.6.112.139:7281", "8.6.112.154:891", "8.6.112.182:891",
        "188.114.98.27:890", "188.114.99.119:891",
        "[2606:4700:100::a29f:c102]:2408", "[2606:4700:100::a29f:c108]:2408",
        "[2606:4700:100::a29f:c10a]:2408",
        "188.114.96.62:894", "188.114.97.124:903", "188.114.96.1:1701",
        "162.159.195.54:864", "162.159.192.60:859", "188.114.97.114:880",
        "162.159.192.121:903", "188.114.97.121:968", "188.114.98.53:890",
        "162.159.195.56:864", "162.159.195.68:864", "162.159.192.132:903",
        "188.114.97.174:880", "188.114.96.76:878", "188.114.96.166:878",
        "188.114.96.206:878", "162.159.192.1:2408", "8.34.146.150:1701",
        "162.159.192.96:939",
    )
    /** Fast pool is materialized from a distinct list so duplicate additions cannot multiply probes. */
    val FAST_ENDPOINTS: String = FAST_ENDPOINT_SEEDS.distinct().joinToString(",")
    // WARP Plus All pool requested for the WARPSCOUT-style scan. Keep this
    // list separate from the MASQUE endpoint path and from Fast's curated
    // list: All deliberately scans these four /24 ranges with the primary
    // ports first, then samples the extended port list if needed.
    val ALL_ENDPOINT_SUBNETS = listOf(
        "188.114.96",
        "188.114.97",
        "162.159.195",
        "8.6.112",
    )
    val ALL_ENDPOINT_PORTS = listOf(2408, 500, 1701, 4500)
    /** Alternate ports used by WARPSCOUT only after primary ports are silent. */
    val WARPSCOUT_EXTENDED_ENDPOINT_PORTS = listOf(
        854, 859, 864, 878, 880, 890, 891, 894, 903, 908,
        928, 934, 939, 942, 943, 945, 946, 955, 968, 987,
        988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1843,
        2371, 2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233, 5279,
        5956, 7103, 7152, 7156, 7281, 7559, 8319, 8742, 8854, 8886,
    )

    private fun endpointsFor(ports: List<Int>): String = ALL_ENDPOINT_SUBNETS
        .flatMap { subnet -> (0..255).flatMap { host -> ports.map { port -> "$subnet.$host:$port" } } }
        .distinct()
        .joinToString("\n")

    /** Primary All pool: 4 /24 ranges × the four primary ports. */
    val ALL_ENDPOINTS: String = endpointsFor(ALL_ENDPOINT_PORTS)

    const val DEFAULT_KEEP_ALIVE = 5
    const val DEFAULT_FINAL_MASK = """{"udp":[{"type":"noise","settings":{"reset":"30-60","noise":[{"rand":"50-100","randRange":"16-255","delay":"1-3"},{"rand":"50-100","randRange":"16-255","delay":"1-3"},{"rand":"50-100","randRange":"16-255","delay":"1-3"}]}}]}"""
}

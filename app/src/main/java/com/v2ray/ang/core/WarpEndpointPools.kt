package com.v2ray.ang.core

/** Small fallback address pool used when the bundled endpoint engine is unavailable. */
object WarpEndpointPools {
    private val subnets = listOf("188.114.96", "188.114.97", "162.159.195", "8.6.112")
    private val ports = listOf(2408, 500, 1701, 4500)

    val AWG_FALLBACK: String = subnets
        .flatMap { subnet -> (0..255).flatMap { host -> ports.map { port -> "$subnet.$host:$port" } } }
        .distinct()
        .joinToString("\n")
}

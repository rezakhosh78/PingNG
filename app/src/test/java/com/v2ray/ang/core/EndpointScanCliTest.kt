package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointScanCliTest {
    @Test
    fun reportKeepsOnlyWorkingEndpointsSortedByTunnelPing() {
        val report = listOf(
            "# WARP endpoints: 2 working / 3 probed",
            "ENDPOINT               ENDPOINT PING TUN PING  LOSS   SEEN AS NODE NODE LOCATION",
            "188.114.98.58:2408     18ms          62ms      0%     DE      FRA  Frankfurt",
            "8.6.112.31:500         25ms          38ms      0%     NL      AMS  Amsterdam",
            "# 1 torn down (handshake ok, data flowed, then cut and never recovered)",
            "188.114.99.1:500      1ms           2ms       0%     DE      FRA  Frankfurt",
        )

        val candidates = EndpointScannerCli.parseWorkingReport(report)

        assertEquals(listOf("8.6.112.31:500", "188.114.98.58:2408"), candidates.map { it.endpoint })
        assertEquals(listOf(38L, 62L), candidates.map { it.latencyMs })
        assertEquals(candidates, EndpointScannerCli.decodeCandidates(EndpointScannerCli.encodeCandidates(candidates)))
    }
}

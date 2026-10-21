package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointScanCliTest {
    @Test
    fun scanOptionDefaultsMatchEndpointScannerDefaults() {
        assertEquals(
            listOf(
                "--timeout-seconds", "2",
                "--parallel-jobs", "30",
                "--ip-family", "ipv4",
                "--port-scan-mode", "first-reachable",
            ),
            EndpointScanOptions().toCliArguments(),
        )
    }

    @Test
    fun legacySavedDefaultsUseThirtyWorkersButCustomJobsArePreserved() {
        assertEquals(
            30,
            endpointScanOptionsFromValues(listOf("2", "10", "IPV4", "FIRST_REACHABLE")).parallelJobs,
        )
        assertEquals(
            10,
            endpointScanOptionsFromValues(listOf("2", "10", "IPV6", "ALL_KNOWN_PORTS")).parallelJobs,
        )
    }

    @Test
    fun wireGuardScanRequiresDurableTunnelProbe() {
        val arguments = buildEndpointScanArguments(
            binaryPath = "/private/native/libendpointscanner.so",
            protocol = "wg",
            accountFilePath = "/private/account.json",
            scanOptions = EndpointScanOptions(),
        )

        assertEquals(true, "-P" in arguments)
        assertEquals(listOf("--parallel-jobs", "30"), arguments.windowed(2).first { it.first() == "--parallel-jobs" })
    }

    @Test
    fun masqueScanDoesNotAddWireGuardDurabilityProbe() {
        val arguments = buildEndpointScanArguments(
            binaryPath = "/private/native/libendpointscanner.so",
            protocol = "masque-h2",
            accountFilePath = "/private/account.json",
            scanOptions = EndpointScanOptions(),
        )

        assertEquals(false, "-P" in arguments)
        assertEquals(false, "--port-scan-mode" in arguments)
    }

    @Test
    fun scanOptionsBuildEndpointScannerArgumentsAndOmitMasquePortMode() {
        val options = EndpointScanOptions(
            timeoutSeconds = 7,
            parallelJobs = 18,
            ipFamily = EndpointIpFamily.IPV6,
            portScanMode = EndpointPortScanMode.ALL_KNOWN_PORTS,
        )

        assertEquals(
            listOf(
                "--timeout-seconds", "7",
                "--parallel-jobs", "18",
                "--ip-family", "ipv6",
                "--port-scan-mode", "all-known-ports",
            ),
            options.toCliArguments(),
        )
        assertEquals(
            listOf(
                "--timeout-seconds", "7",
                "--parallel-jobs", "18",
                "--ip-family", "ipv6",
            ),
            options.toCliArguments(includePortScanMode = false),
        )
    }

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

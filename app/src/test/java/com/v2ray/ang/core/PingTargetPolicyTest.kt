package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PingTargetPolicyTest {
    @Test
    fun joinedIpv4AlternativesAreDetectedBeforePassingConfigToNativeXray() {
        assertTrue(
            containsUnsupportedJoinedIpv4Address(
                """{"outbounds":[{"settings":{"vnext":[{"address":"8.8.8.8+8.8.4.4"}]}}]}""",
            ),
        )
        assertFalse(
            containsUnsupportedJoinedIpv4Address(
                """{"outbounds":[{"settings":{"vnext":[{"address":"8.8.8.8"},{"address":"8.8.4.4"}]}}]}""",
            ),
        )
        assertFalse(
            containsUnsupportedJoinedIpv4Address(
                """{"outbounds":[{"settings":{"vnext":[{"address":"node.example.org"}]}}]}""",
            ),
        )
    }

    @Test
    fun batchPingFilterRemovesEveryMasterDnsEngineAndKeepsOtherOrMissingProfiles() {
        val profiles = mapOf(
            "masterdns" to profile("MasterDNS"),
            "stormdns" to profile("StormDNS"),
            "dnstt" to profile("DNSTT"),
            "regular" to profile("VLESS"),
            "openvpn" to ProfileItem(configType = EConfigType.OPENVPN),
        )

        assertEquals(
            listOf("regular", "missing"),
            filterPingableGuids(listOf("masterdns", "stormdns", "dnstt", "regular", "openvpn", "missing")) {
                profiles[it]
            },
        )
        assertEquals(
            listOf("openvpn"),
            filterPingableGuids(
                listOf("openvpn"),
                includeOpenVpnProfiles = true,
            ) { profiles[it] },
        )
        assertEquals(
            listOf("regular", "missing"),
            filterPingableGuids(
                listOf("openvpn", "regular", "missing"),
                includeOpenVpnProfiles = false,
            ) { profiles[it] },
        )
        assertFalse(isInvalidPingResult(profile("MasterDNS"), -1L))
        assertTrue(isInvalidPingResult(profile("VLESS"), -1L))
        assertFalse(isInvalidPingResult(profile("VLESS"), 42L))
        assertFalse(isProfilePingable(profiles.getValue("openvpn")))
        assertFalse(isInvalidPingResult(profiles.getValue("openvpn"), -1L))
        assertTrue(canDisplayProfilePing(profiles.getValue("openvpn")))
        assertTrue(canProbeOpenVpnProfile("openvpn", "openvpn", isTunnelConnected = true))
        assertFalse(canProbeOpenVpnProfile("openvpn", "openvpn", isTunnelConnected = false))
        assertFalse(canProbeOpenVpnProfile("openvpn", "other", isTunnelConnected = true))
    }

    @Test
    fun manualConnectionCheckUsesTheDnsBridgeSocksPortWithoutChangingBatchPolicy() {
        assertEquals(MasterDnsBridge.PORT, dnsTunnelConnectionProbePort(profile("MasterDNS")))
        assertEquals(MasterDnsBridge.PORT, dnsTunnelConnectionProbePort(profile("StormDNS")))
        assertEquals(MasterDnsBridge.PORT, dnsTunnelConnectionProbePort(profile("DNSTT")))
        assertEquals(
            MasterDnsBridge.SSH_SOCKS_PORT,
            dnsTunnelConnectionProbePort(profile("DNSTT").apply { dnsTunnelMode = "SSH" }),
        )
        assertEquals(null, dnsTunnelConnectionProbePort(profile("VLESS")))
        assertFalse(isProfilePingable(profile("MasterDNS")))
    }

    @Test
    fun soarUsesItsBridgeForConnectionAndBatchDelayChecks() {
        val soar = ProfileItem(
            configType = EConfigType.SOCKS,
            description = SoarBridge.LABEL,
            server = "127.0.0.1",
            serverPort = SoarBridge.PORT.toString(),
        )

        assertEquals(listOf("soar"), filterPingableGuids(listOf("soar")) { soar })
        assertEquals(SoarBridge.PORT, dnsTunnelConnectionProbePort(soar))
        assertTrue(isProfilePingable(soar))
        assertTrue(isInvalidPingResult(soar, -1L))
        assertFalse(shouldUseV2rayNgTcpPreflight(soar))
        assertFalse(shouldUseV2rayNgRealDelayProbe(soar))
    }

    @Test
    fun upstreamTcpPreflightAppliesToStandardProfilesButNotOpenVpnOrSpecialRoutes() {
        val standard = ProfileItem(
            configType = EConfigType.VMESS,
            server = "proxy.example",
            serverPort = "443",
        )
        assertTrue(shouldUseV2rayNgTcpPreflight(standard))
        assertTrue(shouldUseV2rayNgRealDelayProbe(standard))

        val openVpn = ProfileItem(configType = EConfigType.OPENVPN)
        assertFalse(shouldUseV2rayNgTcpPreflight(openVpn))
        assertFalse(shouldUseV2rayNgRealDelayProbe(openVpn))
        assertFalse(shouldUseV2rayNgTcpPreflight(standard.copy(alpn = "h3")))
        assertFalse(shouldUseV2rayNgTcpPreflight(standard.copy(psiphonEnabled = true)))
        assertFalse(shouldUseV2rayNgRealDelayProbe(standard.copy(bepassEnabled = true)))
    }

    private fun profile(description: String) = ProfileItem(
        configType = EConfigType.SOCKS,
        description = description,
    )
}

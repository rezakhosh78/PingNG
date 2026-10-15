package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PingTargetPolicyTest {
    @Test
    fun batchPingFilterRemovesEveryMasterDnsEngineAndKeepsOtherOrMissingProfiles() {
        val profiles = mapOf(
            "masterdns" to profile("MasterDNS"),
            "stormdns" to profile("StormDNS"),
            "dnstt" to profile("DNSTT"),
            "regular" to profile("VLESS"),
        )

        assertEquals(
            listOf("regular", "missing"),
            filterPingableGuids(listOf("masterdns", "stormdns", "dnstt", "regular", "missing")) {
                profiles[it]
            },
        )
        assertFalse(isInvalidPingResult(profile("MasterDNS"), -1L))
        assertTrue(isInvalidPingResult(profile("VLESS"), -1L))
        assertFalse(isInvalidPingResult(profile("VLESS"), 42L))
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

    private fun profile(description: String) = ProfileItem(
        configType = EConfigType.SOCKS,
        description = description,
    )
}

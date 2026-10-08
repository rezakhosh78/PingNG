package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AmneziaWgFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgWarpConfigTest {
    @Test
    fun renderMatchesTheGeneratedAmneziaWgWarpProfile() {
        val account = WarpAccount(
            privateKey = "private-key",
            publicKey = "client-public-key",
            localAddress = "172.16.0.2/32,2606:4700:110::2/128",
            peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            endpoint = "8.39.214.129:500",
            endpointHost = "8.39.214.129",
            endpointPort = 500,
            reserved = "",
        )

        val config = AwgWarpConfig.render(account, "8.39.214.129:500")

        assertTrue(config.startsWith("# Generated AmneziaWG WARP Config\n[Interface]"))
        assertEquals(account.privateKey, AmneziaWgFmt.value(config, "Interface", "PrivateKey"))
        assertEquals("172.16.0.2/32, 2606:4700:110::2/128", AmneziaWgFmt.value(config, "Interface", "Address"))
        assertEquals(AwgWarpConfig.DEFAULT_DNS, AmneziaWgFmt.value(config, "Interface", "DNS"))
        assertEquals("1280", AmneziaWgFmt.value(config, "Interface", "MTU"))
        assertEquals("5", AmneziaWgFmt.value(config, "Interface", "Jc"))
        assertEquals("10", AmneziaWgFmt.value(config, "Interface", "Jmin"))
        assertEquals("40", AmneziaWgFmt.value(config, "Interface", "Jmax"))
        assertEquals("0", AmneziaWgFmt.value(config, "Interface", "S1"))
        assertEquals("0", AmneziaWgFmt.value(config, "Interface", "S2"))
        assertEquals("1", AmneziaWgFmt.value(config, "Interface", "H1"))
        assertEquals("2", AmneziaWgFmt.value(config, "Interface", "H2"))
        assertEquals("3", AmneziaWgFmt.value(config, "Interface", "H3"))
        assertEquals("4", AmneziaWgFmt.value(config, "Interface", "H4"))
        assertEquals("0.0.0.0/0, ::/0", AmneziaWgFmt.value(config, "Peer", "AllowedIPs"))
        assertEquals("8.39.214.129:500", AmneziaWgFmt.value(config, "Peer", "Endpoint"))
    }

    @Test
    fun markerOnlyRecognizesAwgWarpProfiles() {
        val profile = ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            description = AwgWarpConfig.DESCRIPTION
        }

        assertTrue(AwgWarpConfig.isProfile(profile))
        assertTrue(!AwgWarpConfig.isProfile(ProfileItem.create(EConfigType.AMNEZIAWG)))
    }
}

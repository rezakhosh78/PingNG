package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireguardFmtTest {
    @Test
    fun preservesRemoteDnsInWireguardUri() {
        val profile = ProfileItem.create(EConfigType.WIREGUARD).apply {
            remarks = "DNS test"
            server = "vpn.example.org"
            serverPort = "51820"
            secretKey = "test-private-key"
            publicKey = "test-public-key"
            localAddress = "10.0.0.2/32"
            remoteDNS = "9.9.9.9, 2620:fe::fe"
        }

        val uri = "wg://${WireguardFmt.toUri(profile)}"
        val parsed = WireguardFmt.parse(uri)

        assertEquals("9.9.9.9,2620:fe::fe", parsed?.remoteDNS)
    }

    @Test
    fun readsRemoteDnsFromConfAndUsesDefaultWhenMissing() {
        val conf = """
            [Interface]
            PrivateKey = test-private-key
            Address = 10.0.0.2/32
            DNS = 9.9.9.9, 2620:fe::fe

            [Peer]
            PublicKey = test-public-key
            Endpoint = vpn.example.org:51820
        """.trimIndent()

        val parsed = WireguardFmt.parseWireguardConfFile(conf)
        val legacyProfile = WireguardFmt.parseWireguardConfFile(conf.replace("DNS = 9.9.9.9, 2620:fe::fe\n", ""))

        assertEquals("9.9.9.9, 2620:fe::fe", parsed.remoteDNS)
        assertEquals(AppConfig.WIREGUARD_LOCAL_REMOTE_DNS, legacyProfile.remoteDNS)
        assertTrue(legacyProfile.remoteDNS.orEmpty().contains("1.1.1.1"))
    }
    @Test
    fun readsBracketedIpv6EndpointFromWarpConf() {
        val conf = """
            [Interface]
            PrivateKey = test-private-key
            Address = 172.16.0.2/32

            [Peer]
            PublicKey = test-public-key
            Endpoint = [2606:4700:d0::a29f:c001]:2408
        """.trimIndent()
        val parsed = WireguardFmt.parseWireguardConfFile(conf)
        assertEquals("2606:4700:d0::a29f:c001", parsed.server)
        assertEquals("2408", parsed.serverPort)
    }

}

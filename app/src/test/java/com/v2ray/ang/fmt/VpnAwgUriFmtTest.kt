package com.v2ray.ang.fmt

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class VpnAwgUriFmtTest {
    private val config = """[Interface]
PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
Address = 10.8.1.2/32
Jc = 6
I1 = <r 217>

[Peer]
PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = vpn.example.org:43923"""

    @Test
    fun importsSubscriptionLinkAsAmneziaWgAndPreservesParameters() {
        val payload = Base64.getEncoder().encodeToString(config.toByteArray(StandardCharsets.UTF_8))
        val profile = VpnAwgUriFmt.parse("vpn://$payload")
        assertEquals(EConfigType.AMNEZIAWG, profile?.configType)
        assertEquals(config, profile?.amneziawgConfig)
        assertEquals("vpn.example.org", profile?.server)
    }

    @Test
    fun acceptsUrlSafePayloadAndRejectsUnrelatedOrIncompleteData() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(config.toByteArray(StandardCharsets.UTF_8))
        assertEquals(EConfigType.AMNEZIAWG, VpnAwgUriFmt.parse("VPN://$payload#node")?.configType)
        assertNull(VpnAwgUriFmt.parse("vpn://invalid"))
        assertNull(VpnAwgUriFmt.parse("vpn://" + Base64.getEncoder().encodeToString("hello".toByteArray())))
    }
}

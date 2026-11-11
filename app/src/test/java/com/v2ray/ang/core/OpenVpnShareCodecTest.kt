package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenVpnShareCodecTest {

    @Test
    fun `share link round trips title and complete inline OpenVPN config`() {
        val config = """client
remote vpn.example 1194 udp
<ca>
-----BEGIN CERTIFICATE-----
test-ca
-----END CERTIFICATE-----
</ca>
<key>
-----BEGIN PRIVATE KEY-----
test-private-key
-----END PRIVATE KEY-----
</key>
"""
        val profile = ProfileItem.create(EConfigType.OPENVPN).copy(
            remarks = "Secure VPN",
            openVpnConfig = config,
        )

        val encoded = OpenVpnShareCodec.encode(profile)
        val decoded = OpenVpnShareCodec.decode(encoded)
        val parsed = OpenVpnShareCodec.parse(encoded)

        assertTrue(encoded.startsWith("PingNG-OpenVPN://"))
        assertTrue(encoded.startsWith(OpenVpnShareCodec.PREFIX + "p/"))
        assertTrue(encoded.substringAfter(OpenVpnShareCodec.PREFIX + "p/").all { it.isLetterOrDigit() || it in "_-" })
        assertEquals("Secure VPN", decoded?.remarks)
        assertEquals(config, decoded?.config)
        assertEquals(config, parsed?.openVpnConfig)
        assertEquals("Secure VPN", parsed?.remarks)
    }

    @Test
    fun `share link compresses larger profile text`() {
        val config = "client\nremote vpn.example 1194 udp\n" + "# common transferable note\n".repeat(1_000)
        val encoded = OpenVpnShareCodec.encode(
            ProfileItem.create(EConfigType.OPENVPN).copy(openVpnConfig = config),
        )

        assertTrue(encoded.length < config.length)
        assertEquals(config, OpenVpnShareCodec.decode(encoded)?.config)
    }

    @Test
    fun `invalid links are rejected and unrelated input is ignored`() {
        assertNull(OpenVpnShareCodec.decode("PingNG-OpenVPN://bad"))
        assertNull(OpenVpnShareCodec.parse("vmess://example"))
        assertTrue(OpenVpnShareCodec.PREFIX == "PingNG-OpenVPN://")
    }
}

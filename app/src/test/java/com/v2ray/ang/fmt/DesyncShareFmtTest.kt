package com.v2ray.ang.fmt

import com.v2ray.ang.dto.V2rayNShareItem
import com.v2ray.ang.dto.VmessQRCode
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesyncShareFmtTest {
    private val customArgs = "--proto=tls --fake -1 --tls-sni cover.example --delay-range 1-3"

    @Test
    fun vlessUriPreservesCustomDesyncSettingsWhenImported() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "WS TLS"
            server = "proxy.example.com"
            serverPort = "443"
            password = "profile-id"
            network = "ws"
            security = "tls"
            pingNgProfile = "Custom"
            pingNgDesyncArgs = customArgs
        }

        val imported = VlessFmt.parse("vless://${VlessFmt.toUri(profile)}")

        assertNotNull(imported)
        assertEquals("Custom", imported?.pingNgProfile)
        assertEquals(customArgs, imported?.pingNgDesyncArgs)
    }

    @Test
    fun socksUriPreservesCustomDesyncSettingsWhenImported() {
        val profile = ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = "SOCKS"
            server = "proxy.example.com"
            serverPort = "1080"
            username = "user"
            password = "pass"
            pingNgProfile = "Custom"
            pingNgDesyncArgs = customArgs
        }

        val imported = SocksFmt.parse("socks://${SocksFmt.toUri(profile)}")

        assertNotNull(imported)
        assertEquals("Custom", imported?.pingNgProfile)
        assertEquals(customArgs, imported?.pingNgDesyncArgs)
    }

    @Test
    fun queryImporterInfersCustomWhenOnlyRawArgumentsArePresent() {
        val base = FmtBase()
        val imported = ProfileItem.create(EConfigType.TROJAN)

        base.getDesyncFromQuery(imported, mapOf("pngargs" to customArgs))

        assertEquals("Custom", imported.pingNgProfile)
        assertEquals(customArgs, imported.pingNgDesyncArgs)
    }

    @Test
    fun vmessJsonShareCarriesPingNgExtensionFields() {
        val qr = VmessQRCode(png = "Custom", pngargs = customArgs)

        val imported = JsonUtil.fromJson(JsonUtil.toJson(qr), VmessQRCode::class.java)

        assertEquals("Custom", imported?.png)
        assertEquals(customArgs, imported?.pngargs)
    }

    @Test
    fun v2rayNJsonCanImportPingNgExtensionFields() {
        val json = """
            {
              "ConfigType": 5,
              "Address": "proxy.example.com",
              "Port": 443,
              "Network": "ws",
              "StreamSecurity": "tls",
              "PingNgProfile": "Custom",
              "PingNgDesyncArgs": "$customArgs"
            }
        """.trimIndent()
        val item = JsonUtil.fromJson(json, V2rayNShareItem::class.java)

        val imported = item?.toProfileItem()

        assertNotNull(imported)
        assertEquals("Custom", imported?.pingNgProfile)
        assertEquals(customArgs, imported?.pingNgDesyncArgs)
        assertTrue(imported?.network == "ws")
    }
}

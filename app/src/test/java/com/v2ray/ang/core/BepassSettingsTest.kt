package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BepassSettingsTest {
    @Test
    fun supportsOnlyTcpTlsOrRealityProxyProfiles() {
        listOf(EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN, EConfigType.SHADOWSOCKS).forEach { type ->
            assertTrue(BepassSettings.isSupported(type, "tcp", "tls"))
            assertTrue(BepassSettings.isSupported(type, "ws", "reality"))
        }

        assertFalse(BepassSettings.isSupported(EConfigType.VLESS, "kcp", "tls"))
        assertFalse(BepassSettings.isSupported(EConfigType.VLESS, "tcp", "none"))
        assertFalse(BepassSettings.isSupported(EConfigType.SOCKS, "tcp", "tls"))
        assertFalse(BepassSettings.isSupported(EConfigType.HYSTERIA2, "tcp", "tls"))
    }

    @Test
    fun validatesWorkersAndEmitsDeviceOnlyListeners() {
        assertNull(BepassSettings().validate())
        assertEquals(
            BepassSettings().remoteDnsAddress,
            BepassSettings.fromJson("""{"workerEnabled":false}""").remoteDnsAddress,
        )
        assertEquals("workerAddress", BepassSettings(workerEnabled = true).validate())
        assertEquals("workerIpPortAddress", BepassSettings(
            workerEnabled = true,
            workerAddress = "https://edge.example/workers",
            workerIpPortAddress = "bad-address",
        ).validate())

        val runtime = JsonParser.parseString(BepassSettings().toRuntimeJson(18291)).asJsonObject
        assertEquals("127.0.0.1:18291", runtime.get("BindAddress").asString)
        assertEquals("127.0.0.1", runtime.get("UDPBindAddress").asString)
        assertFalse(runtime.get("EnableLowLevelSockets").asBoolean)
    }

    @Test
    fun attachesOneLoopbackSocksOutboundAndPreservesExistingSockOptions() {
        val profile = ProfileItem(
            configType = EConfigType.VLESS,
            bepassEnabled = true,
            network = "tcp",
            security = "tls",
        )
        val config = JsonParser.parseString(
            """{"outbounds":[{"tag":"proxy","protocol":"vless","streamSettings":{"network":"tcp","sockopt":{"mark":12}}}]}""",
        ).asJsonObject

        assertTrue(BepassConfigPolicy.attach(config, profile, 18291))
        assertTrue(BepassConfigPolicy.attach(config, profile, 18291))

        val outbounds = config.getAsJsonArray("outbounds")
        assertEquals(2, outbounds.size())
        val primary = outbounds.first().asJsonObject
        val sockopt = primary.getAsJsonObject("streamSettings").getAsJsonObject("sockopt")
        assertEquals(12, sockopt.get("mark").asInt)
        assertEquals(BepassConfigPolicy.SOCKS_OUTBOUND_TAG, sockopt.get("dialerProxy").asString)
        val bepassOutbound = outbounds.last().asJsonObject
        assertEquals("socks", bepassOutbound.get("protocol").asString)
        val server = bepassOutbound.getAsJsonObject("settings").getAsJsonArray("servers").first().asJsonObject
        assertEquals("127.0.0.1", server.get("address").asString)
        assertEquals(18291, server.get("port").asInt)
    }

    @Test
    fun refusesToReplaceAnExistingDialerProxyChain() {
        val profile = ProfileItem(
            configType = EConfigType.VMESS,
            bepassEnabled = true,
            network = "tcp",
            security = "tls",
        )
        val config = JsonParser.parseString(
            """{"outbounds":[{"tag":"proxy","protocol":"vmess","streamSettings":{"network":"tcp","sockopt":{"dialerProxy":"pingng-desync"}}}]}""",
        ).asJsonObject

        assertThrows(IllegalStateException::class.java) {
            BepassConfigPolicy.attach(config, profile, 18291)
        }
    }

    @Test
    fun removesOnlyTheAutomaticTcpFinalMaskAndRejectsAnExplicitOne() {
        val profile = ProfileItem(
            configType = EConfigType.VLESS,
            bepassEnabled = true,
            network = "tcp",
            security = "tls",
        )
        val config = JsonParser.parseString(
            """{"outbounds":[{"tag":"proxy","protocol":"vless","streamSettings":{"network":"tcp","finalmask":{"tcp":[{"type":"fragment"}],"udp":[{"type":"noise"}]}}}]}""",
        ).asJsonObject

        BepassConfigPolicy.attach(config, profile, 18291)

        val stream = config.getAsJsonArray("outbounds").first().asJsonObject.getAsJsonObject("streamSettings")
        val masks = stream.getAsJsonObject("finalmask")
        assertFalse(masks.has("tcp"))
        assertTrue(masks.has("udp"))

        profile.finalMask = """{"tcp":[{"type":"fragment"}]}"""
        assertThrows(IllegalStateException::class.java) {
            BepassConfigPolicy.attach(config, profile, 18291)
        }
    }
}

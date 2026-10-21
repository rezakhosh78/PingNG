package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsiphonRoutingPolicyTest {
    @Test fun awgSecondLayerNeverFallsBackToDirectIpBeforeItsGatewayIsReady() {
        assertTrue(PsiphonProbeRoutePolicy.waitForAmneziaGateway(EConfigType.AMNEZIAWG, true, false, 0))
        assertTrue(PsiphonProbeRoutePolicy.waitForAmneziaGateway(EConfigType.AMNEZIAWG, false, true, 0))
        assertFalse(PsiphonProbeRoutePolicy.waitForAmneziaGateway(EConfigType.AMNEZIAWG, true, false, 45678))
        assertFalse(PsiphonProbeRoutePolicy.waitForAmneziaGateway(EConfigType.AMNEZIAWG, false, false, 0))
        assertFalse(PsiphonProbeRoutePolicy.waitForAmneziaGateway(EConfigType.VLESS, true, false, 0))
    }

    @Test fun awgHealthChecksUseTheSameGatewayAsVpnTraffic() {
        assertEquals(
            45678,
            PsiphonProbeRoutePolicy.socksPort(
                EConfigType.AMNEZIAWG,
                psiphonEnabled = true,
                torEnabled = false,
                amneziaGatewayPort = 45678,
                psiphonPort = 33251,
                torPort = 0,
            ),
        )
    }

    @Test fun ordinarySecondLayerProfilesKeepTheirOwnSocksProbe() {
        assertEquals(
            33251,
            PsiphonProbeRoutePolicy.socksPort(
                EConfigType.VLESS,
                psiphonEnabled = true,
                torEnabled = false,
                amneziaGatewayPort = 45678,
                psiphonPort = 33251,
                torPort = 0,
            ),
        )
        assertEquals(
            19090,
            PsiphonProbeRoutePolicy.socksPort(
                EConfigType.AMNEZIAWG,
                psiphonEnabled = false,
                torEnabled = true,
                amneziaGatewayPort = 19090,
                psiphonPort = 0,
                torPort = 9050,
            ),
        )
    }

    @Test fun liveEgressProbeUrlsKeepDistinctConfiguredFallbacksInOrder() {
        assertEquals(
            listOf("https://primary.example/check", "https://backup.example/check"),
            PsiphonRoutingPolicy.liveEgressProbeUrls(
                " https://primary.example/check ",
                "https://primary.example/check",
                "https://backup.example/check",
                " ",
            ),
        )
    }

    @Test fun dnsUsesDnsOutboundAndAllOtherUdpUsesFirstHopForAppCompatibility() {
        val outbounds = JsonArray()
        val dnsTag = PsiphonRoutingPolicy.ensureDnsOutbound(outbounds)

        assertEquals("dns-out", dnsTag)
        assertEquals("dns", outbounds[0].asJsonObject.get("protocol").asString)
        val rules = PsiphonRoutingPolicy.userUdpRules(
            dnsOutboundTag = dnsTag,
            udpOutboundTag = "proxy",
        )
        assertEquals(2, rules.size)
        assertEquals("53", rules[0].get("port").asString)
        assertEquals(dnsTag, rules[0].get("outboundTag").asString)
        assertEquals("udp", rules[1].get("network").asString)
        assertFalse(rules[1].has("port"))
        assertEquals("proxy", rules[1].get("outboundTag").asString)
    }

    @Test fun reusesConfiguredDnsOutboundOrAvoidsItsTagCollision() {
        val configured = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", "custom-dns")
                addProperty("protocol", "dns")
            })
        }
        assertEquals("custom-dns", PsiphonRoutingPolicy.ensureDnsOutbound(configured))
        assertEquals(1, configured.size())

        val collision = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", "dns-out")
                addProperty("protocol", "freedom")
            })
        }
        val tag = PsiphonRoutingPolicy.ensureDnsOutbound(collision)
        assertTrue(tag.startsWith("pingng-psiphon-dns-out-"))
        assertEquals("dns", collision.last().asJsonObject.get("protocol").asString)
    }

    @Test fun missingDnsRouteStillKeepsRemainingUdpOnFirstHop() {
        val rules = PsiphonRoutingPolicy.userUdpRules(
            dnsOutboundTag = null,
            udpOutboundTag = "proxy",
        )
        assertEquals(1, rules.size)
        assertEquals("udp", rules[0].get("network").asString)
        assertFalse(rules[0].has("port"))
        assertEquals("proxy", rules[0].get("outboundTag").asString)
    }
}

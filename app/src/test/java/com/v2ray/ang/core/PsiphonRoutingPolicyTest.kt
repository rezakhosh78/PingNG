package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsiphonRoutingPolicyTest {
    @Test fun dnsKeepsItsRouteAndOtherUdpIsBlocked() {
        val outbounds = JsonArray()
        val dnsTag = PsiphonRoutingPolicy.ensureDnsOutbound(outbounds)
        val blockTag = PsiphonRoutingPolicy.ensureUdpBlockOutbound(outbounds)

        assertEquals("dns-out", dnsTag)
        assertEquals("dns", outbounds[0].asJsonObject.get("protocol").asString)
        assertEquals("blackhole", outbounds[1].asJsonObject.get("protocol").asString)
        val rules = PsiphonRoutingPolicy.userUdpRules(dnsOutboundTag = dnsTag, blockTag = blockTag)
        assertEquals(2, rules.size)
        assertEquals("53", rules[0].get("port").asString)
        assertEquals(dnsTag, rules[0].get("outboundTag").asString)
        assertEquals("udp", rules[1].get("network").asString)
        assertFalse(rules[1].has("port"))
        assertEquals(blockTag, rules[1].get("outboundTag").asString)
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

    @Test fun missingDnsRouteTagStillFailsClosedAndTagCollisionIsAvoided() {
        val outbounds = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", "pingng-psiphon-udp-block")
                addProperty("protocol", "freedom")
            })
        }
        val blockTag = PsiphonRoutingPolicy.ensureUdpBlockOutbound(outbounds)

        assertTrue(blockTag.startsWith("pingng-psiphon-udp-block-"))
        val rules = PsiphonRoutingPolicy.userUdpRules(dnsOutboundTag = null, blockTag = blockTag)
        assertEquals(1, rules.size)
        assertEquals(blockTag, rules.single().get("outboundTag").asString)
    }
}

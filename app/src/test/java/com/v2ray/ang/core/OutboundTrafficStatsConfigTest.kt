package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboundTrafficStatsConfigTest {
    @Test
    fun addsOutboundCountersWithoutDroppingExistingCustomPolicy() {
        val config = JsonParser.parseString(
            """{"routing":{"domainStrategy":"AsIs"},"policy":{"levels":{"8":{"bufferSize":4}},"system":{"statsUserUplink":true}}}"""
        ).asJsonObject

        ensureOutboundTrafficStats(config)

        assertTrue(config.get("stats").isJsonObject)
        assertTrue(config.getAsJsonObject("routing").has("domainStrategy"))
        val system = config.getAsJsonObject("policy").getAsJsonObject("system")
        assertTrue(system.get("statsOutboundUplink").asBoolean)
        assertTrue(system.get("statsOutboundDownlink").asBoolean)
        assertTrue(system.get("statsUserUplink").asBoolean)
    }

    @Test
    fun createsStatsAndPolicyWhenCustomConfigHasNeither() {
        val config = JsonObject()

        ensureOutboundTrafficStats(config)

        assertTrue(config.get("stats").isJsonObject)
        val system = config.getAsJsonObject("policy").getAsJsonObject("system")
        assertTrue(system.get("statsOutboundUplink").asBoolean)
        assertTrue(system.get("statsOutboundDownlink").asBoolean)
    }
}

package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import java.net.ServerSocket

/** Adds a local Xray HTTP hop for the MASQUE core's outer TLS connection. */
object WarpMasqueFinalMaskConfig {
    const val INBOUND_TAG = "pingng-masque-finalmask-in"
    const val OUTBOUND_TAG = "pingng-masque-finalmask-out"

    @Volatile private var listenPort: Int = 0

    fun isEnabled(profile: ProfileItem): Boolean =
        false

    @Synchronized
    fun prepare(profile: ProfileItem, raw: String): String {
        if (!isEnabled(profile)) return raw
        val mask = JsonParser.parseString(profile.finalMask.orEmpty()).takeIf { it.isJsonObject }
            ?: throw IllegalArgumentException("FinalMask must be a JSON object")
        val root = JsonParser.parseString(raw).asJsonObject
        val port = listenPort.takeIf { it in 1..65535 } ?: freePort().also { listenPort = it }

        val inbounds = root.getAsJsonArray("inbounds") ?: JsonArray().also { root.add("inbounds", it) }
        removeTagged(inbounds, INBOUND_TAG)
        inbounds.add(JsonObject().apply {
            addProperty("tag", INBOUND_TAG)
            addProperty("listen", "127.0.0.1")
            addProperty("port", port)
            addProperty("protocol", "http")
            add("settings", JsonObject().apply { addProperty("timeout", 0) })
        })

        val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray().also { root.add("outbounds", it) }
        removeTagged(outbounds, OUTBOUND_TAG)
        outbounds.add(JsonObject().apply {
            addProperty("tag", OUTBOUND_TAG)
            addProperty("protocol", "freedom")
            add("settings", JsonObject())
            add("streamSettings", JsonObject().apply {
                addProperty("network", "tcp")
                add("finalmask", mask)
            })
        })

        val routing = root.getAsJsonObject("routing") ?: JsonObject().also {
            it.addProperty("domainStrategy", "AsIs")
            it.add("rules", JsonArray())
            root.add("routing", it)
        }
        val oldRules = routing.getAsJsonArray("rules") ?: JsonArray()
        val rules = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().also { it.add(INBOUND_TAG) })
                addProperty("outboundTag", OUTBOUND_TAG)
            })
            oldRules.forEach { rule ->
                val alreadyAdded = rule.isJsonObject &&
                    rule.asJsonObject.getAsJsonArray("inboundTag")?.any { it.asString == INBOUND_TAG } == true
                if (!alreadyAdded) add(rule)
            }
        }
        routing.add("rules", rules)
        return root.toString()
    }

    fun proxyUrl(profile: ProfileItem): String? =
        if (isEnabled(profile) && listenPort in 1..65535) "http://127.0.0.1:$listenPort" else null

    @Synchronized
    fun reset() {
        listenPort = 0
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun removeTagged(array: JsonArray, tag: String) {
        for (index in array.size() - 1 downTo 0) {
            val element = array[index]
            if (element.isJsonObject && element.asJsonObject.get("tag")?.asString == tag) {
                array.remove(index)
            }
        }
    }
}

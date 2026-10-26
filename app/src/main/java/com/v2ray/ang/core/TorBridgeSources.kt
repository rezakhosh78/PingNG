package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

/** Resolves the automatic bridge sources exposed by the Tor settings. */
internal object TorBridgeSources {
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L
    private const val MAX_RELAY_BRIDGES = 32
    private const val MOAT_SETTINGS_URL = "https://bridges.torproject.org/moat/circumvention/settings"
    private const val MOAT_BUILTIN_URL = "https://bridges.torproject.org/moat/circumvention/builtin"
    private const val ONIONOO_URL = "https://onionoo.torproject.org/details?type=relay&running=true&fields=fingerprint,or_addresses&limit=100"
    private const val DEFAULT_SNOWFLAKE =
        "snowflake 192.0.2.3:1 2B280B23E1107BB62ABFC40DDCC8824814F80A72"

    data class Plan(val lines: List<String>, val source: String) {
        val needsSnowflake: Boolean get() = lines.any { it.startsWith("snowflake ", ignoreCase = true) }
    }

    /**
     * `auto` mirrors PattNG's combined source, `only` selects Onionoo relays,
     * and `off` selects BridgeDB. Requests use the selected profile's local
     * HTTP proxy, so bridge discovery does not require an independent route.
     */
    fun resolve(context: Context, upstreamPort: Int, sourceMode: String?): Plan {
        require(upstreamPort in 1..65535) { "Tor bridge sources need an active upstream" }
        val mode = sourceMode.orEmpty().lowercase(Locale.ROOT).let {
            if (it in setOf("auto", "only", "off")) it else "auto"
        }
        readCache(context, mode)?.let { cached ->
            if (cached.isNotEmpty()) return Plan(cached, "cached $mode bridge list")
        }

        val collected = linkedSetOf<String>()
        var fromBridgeDb = false
        var fromOnionoo = false
        var fromBundledSnowflake = false
        if (mode != "only") {
            val bridgeDb = runCatching { fetchBridgeDb(upstreamPort) }
                .onFailure { PingNgDiagnostics.record("BridgeDB lookup failed", it) }
                .getOrDefault(emptyList())
            if (bridgeDb.isNotEmpty()) {
                collected += bridgeDb
                fromBridgeDb = true
            } else {
                // Keep a bundled Tor Snowflake bridge available when BridgeDB
                // itself cannot be reached through the chosen upstream.
                collected += DEFAULT_SNOWFLAKE
                fromBundledSnowflake = true
                PingNgDiagnostics.record("BridgeDB unavailable; using Tor's bundled Snowflake bridge")
            }
        }
        if (mode != "off") {
            val relays = runCatching { fetchOnionooRelays(upstreamPort) }
                .onFailure { PingNgDiagnostics.record("Onionoo relay lookup failed", it) }
                .getOrDefault(emptyList())
            if (relays.isNotEmpty()) {
                collected += relays
                fromOnionoo = true
            }
        }
        val lines = collected.take(64)
        if (lines.isEmpty()) throw IllegalStateException("No Tor bridges were returned by ${sourceName(mode)}")
        writeCache(context, mode, lines)
        val source = buildList {
            if (fromBridgeDb) add("BridgeDB")
            if (fromOnionoo) add("Onionoo relays")
            if (fromBundledSnowflake) add("bundled Snowflake fallback")
        }.joinToString(" + ").ifBlank { sourceName(mode) }
        return Plan(lines, source)
    }

    /** Validates custom lines and returns Tor's `Bridge` argument without its directive. */
    fun parseCustomLines(text: String?): List<String> {
        val lines = text.orEmpty().lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefixIgnoreCase("Bridge ").trim() }
            .distinct()
            .toList()
        require(lines.isNotEmpty()) { "Enter at least one Tor bridge line" }
        lines.forEach { line ->
            val valid = if (line.startsWith("snowflake ", ignoreCase = true)) {
                line.split(Regex("\\s+")).size >= 3
            } else {
                val fields = line.split(Regex("\\s+"))
                fields.size >= 2 && fields[0].matches(ADDRESS_WITH_PORT) && fields[1].matches(FINGERPRINT)
            }
            require(valid) {
                "Unsupported bridge line. This build accepts Snowflake and plain address/fingerprint lines only."
            }
        }
        return lines
    }

    private fun fetchBridgeDb(upstreamPort: Int): List<String> {
        val found = linkedSetOf<String>()
        // Read settings first, then the built-in list. The endpoint response
        // shapes differ between BridgeDB releases, so collect only Snowflake
        // bridge directives from their JSON values.
        runCatching {
            val request = JsonObject().apply {
                add("data", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("version", "0.1.0")
                        addProperty("type", "client-transports")
                        add("supported", JsonArray().apply { add("snowflake") })
                    })
                })
            }
            val json = request(
                MOAT_SETTINGS_URL,
                upstreamPort,
                method = "POST",
                body = request.toString(),
                contentType = "application/vnd.api+json",
            )
            found += extractSnowflakeLines(JsonParser.parseString(json))
        }
        runCatching {
            val json = request(MOAT_BUILTIN_URL, upstreamPort)
            found += extractSnowflakeLines(JsonParser.parseString(json))
        }
        return found.toList()
    }

    private fun fetchOnionooRelays(upstreamPort: Int): List<String> {
        val root = JsonParser.parseString(request(ONIONOO_URL, upstreamPort)).asJsonObject
        val relays = root.getAsJsonArray("relays") ?: return emptyList()
        val result = linkedSetOf<String>()
        relays.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val relay = element.asJsonObject
            val fingerprint = relay.get("fingerprint")?.asString.orEmpty()
            if (!fingerprint.matches(FINGERPRINT)) return@forEach
            val addresses = relay.getAsJsonArray("or_addresses") ?: return@forEach
            addresses.forEach { addressElement ->
                if (!addressElement.isJsonPrimitive) return@forEach
                val address = addressElement.asString.trim()
                val port = address.substringAfterLast(':').toIntOrNull() ?: return@forEach
                if (port !in setOf(80, 443) || !isValidAddress(address)) return@forEach
                result += "$address $fingerprint"
            }
        }
        return result.toList().take(MAX_RELAY_BRIDGES)
    }

    private fun extractSnowflakeLines(element: JsonElement): List<String> {
        val candidates = mutableListOf<String>()
        fun collect(current: JsonElement) {
            when {
                current.isJsonPrimitive && current.asJsonPrimitive.isString -> {
                    val value = current.asString
                    candidates += value.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                }
                current.isJsonArray -> current.asJsonArray.forEach(::collect)
                current.isJsonObject -> current.asJsonObject.entrySet().forEach { collect(it.value) }
            }
        }
        collect(element)
        return candidates.mapNotNull { candidate ->
            val line = candidate.removePrefixIgnoreCase("Bridge ").trim()
            line.takeIf { it.startsWith("snowflake ", ignoreCase = true) && it.split(Regex("\\s+")).size >= 3 }
        }.distinct()
    }

    private fun request(
        address: String,
        upstreamPort: Int,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
    ): String {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", upstreamPort))
        val connection = (URL(address).openConnection(proxy) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PingNG Tor bridge discovery")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "HTTP $status from ${URL(address).host}" }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun readCache(context: Context, mode: String): List<String>? = runCatching {
        val file = cacheFile(context, mode)
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return null
        JsonParser.parseString(file.readText()).asJsonArray.mapNotNull { item ->
            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun writeCache(context: Context, mode: String, lines: List<String>) {
        runCatching { cacheFile(context, mode).writeText(JsonArray().apply { lines.forEach { add(it) } }.toString()) }
    }

    private fun cacheFile(context: Context, mode: String): File =
        File(context.noBackupFilesDir, "tor-bridge-cache-$mode.json")

    private fun sourceName(mode: String): String = when (mode) {
        "only" -> "Onionoo relays"
        "off" -> "BridgeDB"
        else -> "BridgeDB + Onionoo relays"
    }

    private fun isValidAddress(address: String): Boolean {
        val host = address.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        if (host.isBlank()) return false
        return host.contains(':') || host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private val ADDRESS_WITH_PORT = Regex("(?:\\[[0-9A-Fa-f:]+]|(?:[0-9]{1,3}\\.){3}[0-9]{1,3}):[0-9]{1,5}")
    private val FINGERPRINT = Regex("[0-9A-Fa-f]{40}")
}

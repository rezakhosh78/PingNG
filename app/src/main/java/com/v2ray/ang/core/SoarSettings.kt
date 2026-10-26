package com.v2ray.ang.core

import com.v2ray.ang.util.JsonUtil
import com.google.gson.JsonParser
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

data class SoarAdvancedSettings(
    val safeQName: Int = 101,
    val maxQName: Int = 253,
    val holdMs: Int = 500,
    val maxInflight: Int = 128,
    val maxPathWindow: Int = 32,
    val maxAnswer: Int = 1232,
    val streamWindowKB: Int = 256,
    val sendBufferKB: Int = 64,
    val maxStreams: Int = 64,
    val sessionIdleSeconds: Int = 90,
    val discoveryWant: Int = 1,
    val discoveryConcurrency: Int = 128,
    val discoveryPaceMs: Int = 1,
    val discoverySampleRanges: Int = 0,
    val discoveryCacheEnabled: Boolean = true,
    val extraDiscoveryResolvers: String = "",
)

data class SoarSettingField(
    val key: String,
    val label: String,
    val value: String,
    val kind: Kind,
    val section: String,
) {
    enum class Kind { NUMBER, BOOLEAN, STRING }
}

object SoarSettings {
    private val numericBounds = mapOf(
        "safeQName" to (40..253), "maxQName" to (40..253), "holdMs" to (100..5_000),
        "maxInflight" to (1..1_024), "maxPathWindow" to (1..1_024), "maxAnswer" to (512..1_232),
        "streamWindowKB" to (1..4_096), "sendBufferKB" to (1..4_096), "maxStreams" to (1..256),
        "sessionIdleSeconds" to (1..119), "discoveryWant" to (1..256),
        "discoveryConcurrency" to (1..128), "discoveryPaceMs" to (1..10_000),
        "discoverySampleRanges" to (0..50_000),
    )
    private val discoveryOnlyNumericKeys = setOf("discoverySampleRanges")

    fun decode(json: String?): SoarAdvancedSettings {
        val defaults = JsonParser.parseString(JsonUtil.toJson(SoarAdvancedSettings())).asJsonObject
        val saved = runCatching { JsonParser.parseString(json.orEmpty()).asJsonObject }.getOrNull()
        saved?.entrySet()?.forEach { (key, value) -> defaults.add(key, value) }
        val settings = JsonUtil.fromJsonSafe(defaults.toString(), SoarAdvancedSettings::class.java)
            ?: SoarAdvancedSettings()
        // Migrate profiles saved with the previous discovery defaults to the faster scan profile.
        return if (settings.discoveryWant == 16 && settings.discoveryConcurrency == 16 &&
            settings.discoveryPaceMs == 20 && !settings.discoveryCacheEnabled
        ) {
            settings.copy(
                discoveryWant = 1,
                discoveryConcurrency = 128,
                discoveryPaceMs = 1,
                discoveryCacheEnabled = true,
            )
        } else {
            settings
        }
    }

    fun encode(settings: SoarAdvancedSettings): String = JsonUtil.toJson(settings)

    /** Shared controls for manual resolver scans and automatic resolver discovery. */
    fun discoveryFields(json: String?): List<SoarSettingField> {
        val s = decode(json)
        return listOf(
            SoarSettingField("discoveryConcurrency", "Concurrent resolver probes", s.discoveryConcurrency.toString(), SoarSettingField.Kind.NUMBER, "Resolver scan"),
            SoarSettingField("discoveryPaceMs", "Delay between resolver probes (milliseconds)", s.discoveryPaceMs.toString(), SoarSettingField.Kind.NUMBER, "Resolver scan"),
            SoarSettingField("discoveryWant", "Healthy resolvers to find", s.discoveryWant.toString(), SoarSettingField.Kind.NUMBER, "Resolver scan"),
            SoarSettingField("discoveryCacheEnabled", "Reuse verified resolvers", s.discoveryCacheEnabled.toString(), SoarSettingField.Kind.BOOLEAN, "Resolver scan"),
        )
    }

    /** Applies transient numeric text drafts after validating that each field contains an integer. */
    fun applyNumericDrafts(
        json: String?,
        drafts: Map<String, String>,
        validateDiscovery: Boolean = true,
    ): String? {
        if (drafts.keys.any { it !in numericBounds }) return null
        var updated = encode(decode(json))
        for ((key, rawValue) in drafts) {
            if (!validateDiscovery && key in discoveryOnlyNumericKeys) continue
            val value = rawValue.toIntOrNull() ?: return null
            updated = update(updated, key, value.toString())
        }
        return updated
    }

    fun fields(json: String?): List<SoarSettingField> {
        val s = decode(json)
        fun number(key: String, label: String, value: Int, section: String = "Client") =
            SoarSettingField(key, label, value.toString(), SoarSettingField.Kind.NUMBER, section)
        return listOf(
            number("safeQName", "Safe DNS query name length", s.safeQName),
            number("maxQName", "Maximum DNS query name length", s.maxQName),
            number("holdMs", "DNS poll hold (milliseconds)", s.holdMs),
            number("maxInflight", "Maximum in-flight DNS queries", s.maxInflight),
            number("maxPathWindow", "Maximum queries per resolver path", s.maxPathWindow),
            number("maxAnswer", "Maximum DNS answer size (bytes)", s.maxAnswer),
            number("streamWindowKB", "Stream receive window (KiB)", s.streamWindowKB),
            number("sendBufferKB", "Stream send buffer (KiB)", s.sendBufferKB),
            number("maxStreams", "Maximum concurrent streams", s.maxStreams),
            number("sessionIdleSeconds", "Session idle timeout (seconds)", s.sessionIdleSeconds),
            number("discoverySampleRanges", "Extra country IPs to sample (0 disables)", s.discoverySampleRanges, "Resolver discovery"),
            SoarSettingField("extraDiscoveryResolvers", "Extra discovery resolvers (one per line)", s.extraDiscoveryResolvers, SoarSettingField.Kind.STRING, "Resolver discovery"),
        )
    }

    fun update(json: String?, key: String, value: String): String {
        val obj = JsonParser.parseString(encode(decode(json))).asJsonObject
        when (key) {
            "discoveryCacheEnabled" -> obj.addProperty(key, value.toBoolean())
            "extraDiscoveryResolvers" -> obj.addProperty(key, value)
            else -> obj.addProperty(key, value.toIntOrNull() ?: return obj.toString())
        }
        return obj.toString()
    }

    fun validationError(settings: SoarAdvancedSettings, tcp: String, validateDiscovery: Boolean = true): String? {
        if (tcp !in setOf("auto", "on", "only", "off")) return "Invalid DNS-over-TCP policy"
        for ((key, range) in numericBounds) {
            if (!validateDiscovery && key in discoveryOnlyNumericKeys) continue
            val value = when (key) {
                "safeQName" -> settings.safeQName
                "maxQName" -> settings.maxQName
                "holdMs" -> settings.holdMs
                "maxInflight" -> settings.maxInflight
                "maxPathWindow" -> settings.maxPathWindow
                "maxAnswer" -> settings.maxAnswer
                "streamWindowKB" -> settings.streamWindowKB
                "sendBufferKB" -> settings.sendBufferKB
                "maxStreams" -> settings.maxStreams
                "sessionIdleSeconds" -> settings.sessionIdleSeconds
                "discoveryWant" -> settings.discoveryWant
                "discoveryConcurrency" -> settings.discoveryConcurrency
                "discoveryPaceMs" -> settings.discoveryPaceMs
                else -> settings.discoverySampleRanges
            }
            if (value !in range) return "Invalid SoarDNS setting: $key (${range.first}..${range.last})"
        }
        if (settings.safeQName > settings.maxQName) return "Safe DNS query name length cannot exceed the maximum"
        // Soar selects answer limits in 48-byte steps starting at 512.
        if ((settings.maxAnswer - 512) % 48 != 0) return "Maximum DNS answer size must be 512 + a multiple of 48"
        if (validateDiscovery && resolverLines(settings.extraDiscoveryResolvers).any { !isValidResolver(it) }) {
            return "Invalid extra discovery resolver"
        }
        return null
    }

    fun resolverLines(text: String): List<String> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .map(::withDefaultPort)
        .toList()

    /** Discovery is only active when no manual resolver has been supplied. */
    fun effectiveDiscoveryEnabled(resolvers: String?, requested: Boolean?): Boolean =
        resolverLines(resolvers.orEmpty()).isEmpty() && (requested ?: true)

    /** Selection shown in the editor. Missing legacy values now default to Iran. */
    fun selectedDiscoveryCountry(resolvers: String?, country: String?): String {
        if (resolverLines(resolvers.orEmpty()).isNotEmpty()) return ""
        val selected = country?.trim().orEmpty()
        if (selected.equals("Auto", ignoreCase = true)) return "Auto"
        return selected.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT) ?: "IR"
    }

    /** Auto discovery defaults to Iran; an explicit Auto selection leaves the country unrestricted. */
    fun effectiveDiscoveryCountry(resolvers: String?, country: String?): String? {
        val selected = selectedDiscoveryCountry(resolvers, country)
        return selected.takeIf { it.isNotBlank() && !it.equals("Auto", ignoreCase = true) }
    }

    /** Retains the user's explicit Auto choice when the profile is saved or shared. */
    fun storedDiscoveryCountry(resolvers: String?, country: String?): String? =
        selectedDiscoveryCountry(resolvers, country).ifBlank { null }

    fun normalizeResolvers(text: String): String = resolverLines(text).joinToString("\n")

    fun withDefaultPort(value: String): String {
        val resolver = value.trim()
        if (resolver.startsWith('[')) {
            val end = resolver.indexOf(']')
            if (end > 0) {
                val suffix = resolver.substring(end + 1)
                return when (suffix) {
                    "" -> "$resolver:53"
                    ":" -> "${resolver}53"
                    else -> resolver
                }
            }
        }
        return when (resolver.count { it == ':' }) {
            0 -> "$resolver:53"
            1 -> if (resolver.endsWith(':')) "${resolver}53" else resolver
            else -> "[$resolver]:53"
        }
    }

    fun isValidResolver(value: String): Boolean = runCatching {
        val normalized = withDefaultPort(value)
        val host: String
        val port: Int
        if (normalized.startsWith('[')) {
            val end = normalized.indexOf(']')
            require(end > 1 && normalized.getOrNull(end + 1) == ':')
            host = normalized.substring(1, end)
            port = normalized.substring(end + 2).toInt()
            require(InetAddress.getByName(host) is Inet6Address)
        } else {
            val split = normalized.lastIndexOf(':')
            require(split > 0)
            host = normalized.substring(0, split)
            port = normalized.substring(split + 1).toInt()
            val octets = host.split('.')
            require(octets.size == 4 && octets.all { it.toIntOrNull()?.let { part -> part in 0..255 } == true })
        }
        require(port in 1..65_535)
        true
    }.getOrDefault(false)
}

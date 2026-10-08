package com.v2ray.ang.core

import android.util.Base64
import com.google.gson.JsonParser
import com.v2ray.ang.AngApplication
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Lossless, versioned sharing for MasterDNS and DNSTT profiles. */
object MasterDnsShareCodec {
    /** Legacy format kept for links that have already been shared. */
    const val PREFIX = "pingng-masterdns://profile?v=1&data="
    /** Compact, compressed format used for new shares. */
    const val COMPACT_PREFIX = "pmd://p?d="
    const val COMPACT_PREFIX_V2 = "pmd://2/"
    const val MASTER_DNS_PREFIX = "PingNG-MasterDNS://"
    const val DNSTT_PREFIX = "PingNG-DNSTT://"
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    private const val MAX_ENCODED_LENGTH = 500_000
    private const val MAX_DECOMPRESSED_LENGTH = 512 * 1024
    /** Pinned separately so future changes to the editable defaults cannot break existing links. */
    private val shareDictionary: ByteArray by lazy {
        AngApplication.application.assets.open("masterdns_share_dictionary_v3.txt").use { it.readBytes() }
    }
    private val defaults: Pair<String, String> by lazy {
        val text = String(shareDictionary, Charsets.UTF_8)
        val split = text.indexOf('\u0000')
        require(split >= 0) { "Invalid DNS share dictionary" }
        text.substring(0, split) to text.substring(split + 1)
    }

    /** Encode the exact default or a reversible prefix/suffix edit instead of repeating 13 KB. */
    private fun compactContent(value: String?, base: String): String? {
        if (value == null) return null
        if (value == base) return "~"
        val prefix = value.commonPrefixWith(base).length
        val suffix = value.substring(prefix).commonSuffixWith(base.substring(prefix)).length
        val delta = "~$prefix,$suffix:${value.substring(prefix, value.length - suffix)}"
        val original = "=$value"
        val baseLines = base.split('\n')
        val valueLines = value.split('\n')
        val changes = valueLines.mapIndexedNotNull { index, line ->
            if (baseLines.getOrNull(index) == line) null else listOf(index, line)
        }
        val lineDelta = "~L" + JsonUtil.toJson(listOf(valueLines.size, changes))
        return listOf(original, delta, lineDelta).minBy { it.length }
    }

    private fun expandContent(value: String?, base: String): String? {
        if (value == null) return null
        if (value == "~") return base
        if (value.startsWith('=')) return value.substring(1)
        if (value.startsWith("~L")) return runCatching {
            val payload = JsonParser.parseString(value.substring(2)).asJsonArray
            if (payload.size() != 2) return null
            val count = payload[0].asInt
            val baseLines = base.split('\n')
            if (count < 0 || count > MAX_DECOMPRESSED_LENGTH) return null
            val lines = MutableList(count) { index -> baseLines.getOrNull(index).orEmpty() }
            payload[1].asJsonArray.forEach { entry ->
                val change = entry.asJsonArray
                if (change.size() != 2) return null
                val index = change[0].asInt
                if (index !in 0 until count) return null
                lines[index] = change[1].asString
            }
            lines.joinToString("\n")
        }.getOrNull()
        if (!value.startsWith('~')) return null
        val separator = value.indexOf(':')
        if (separator < 2) return null
        val lengths = value.substring(1, separator).split(',')
        if (lengths.size != 2) return null
        val prefix = lengths[0].toIntOrNull() ?: return null
        val suffix = lengths[1].toIntOrNull() ?: return null
        if (prefix < 0 || suffix < 0 || prefix + suffix > base.length) return null
        return base.take(prefix) + value.substring(separator + 1) + base.takeLast(suffix)
    }

    private data class Payload(
        val remark: String,
        val engine: String? = null,
        val transport: String? = null,
        val tunnelMode: String? = null,
        val sshUsername: String? = null,
        val sshPassword: String? = null,
        val sshHost: String? = null,
        val sshPort: Int? = null,
        val domain: String,
        val key: String,
        val method: Int,
        val advanced: String?,
        val resolvers: String?,
        val reuseValidResolvers: Boolean? = null,
        val psiphon: Boolean,
        val region: String?,
        val mode: String?,
        val cdnIps: String?,
        val cdnSni: String?,
        val cdnSets: String?,
        val sshKeepaliveSeconds: Int? = null,
    )

    /** The only value rendered from the advanced TOML on the Basic tab. */
    private fun basicTabAdvanced(profile: ProfileItem): String? =
        profile.masterDnsAdvanced?.let { config ->
            MasterDnsSettings.value(config, "MTU_TEST_PARALLELISM")?.let { value ->
                "MTU_TEST_PARALLELISM = $value"
            }
        }

    fun encode(profile: ProfileItem): String {
        require(MasterDnsBridge.isProfile(profile))
        val payload = Payload(
            remark = profile.remarks,
            engine = profile.description,
            transport = profile.dnsTunnelTransport,
            tunnelMode = profile.dnsTunnelMode,
            sshUsername = profile.dnsTunnelSshUsername,
            sshPassword = profile.dnsTunnelSshPassword,
            sshHost = profile.dnsTunnelSshHost,
            sshPort = profile.dnsTunnelSshPort,
            domain = profile.masterDnsDomain.orEmpty(),
            key = profile.masterDnsEncryptionKey.orEmpty(),
            method = profile.masterDnsMethod ?: 1,
            advanced = profile.masterDnsAdvanced,
            resolvers = profile.masterDnsResolvers,
            reuseValidResolvers = profile.masterDnsReuseValidResolvers,
            psiphon = profile.psiphonEnabled,
            region = profile.psiphonRegion,
            mode = profile.psiphonMode,
            cdnIps = profile.psiphonCdnIps,
            cdnSni = profile.psiphonCdnSni,
            cdnSets = profile.psiphonCdnSets,
            sshKeepaliveSeconds = profile.dnsTunnelSshKeepaliveSeconds,
        )
        // Positional fields remove repeated JSON property names while retaining
        // every optional value, including the full resolver list and secrets.
        val json = JsonUtil.toJson(listOf(
            payload.remark, payload.engine, payload.transport, payload.tunnelMode,
            payload.sshUsername, payload.sshPassword, payload.sshHost, payload.sshPort,
            payload.domain, payload.key, payload.method,
            // Shares intentionally contain only the Basic Setting values.
            // MTU_TEST_PARALLELISM is displayed on that tab even though it is
            // stored in the TOML field; preserve only that one line.
            basicTabAdvanced(profile)?.let { "=$it" },
            // MasterDNS uses its bundled resolver list after import. DNSTT,
            // however, requires exactly one resolver and must keep its value.
            if (profile.description == MasterDnsBridge.DNSTT) {
                compactContent(payload.resolvers, defaults.second)
            } else {
                null
            },
            payload.reuseValidResolvers, payload.psiphon,
            payload.region, payload.mode, payload.cdnIps, payload.cdnSni, payload.cdnSets,
            payload.sshKeepaliveSeconds,
        )).toByteArray(Charsets.UTF_8)
        val compressed = deflate(json, shareDictionary)
        val encoded = Base64.encodeToString(compressed, BASE64_FLAGS)
        return (if (profile.description == MasterDnsBridge.DNSTT) DNSTT_PREFIX else MASTER_DNS_PREFIX) + encoded
    }

    fun decode(link: String): ProfileItem? {
        val text = link.trim()
        val brandedEngine = when {
            text.startsWith(DNSTT_PREFIX, ignoreCase = true) -> MasterDnsBridge.DNSTT
            text.startsWith(MASTER_DNS_PREFIX, ignoreCase = true) -> MasterDnsBridge.LABEL
            else -> null
        }
        val (encoded, compressed, positional) = when {
            text.startsWith(DNSTT_PREFIX, ignoreCase = true) ->
                Triple(text.substring(DNSTT_PREFIX.length), true, true)
            text.startsWith(MASTER_DNS_PREFIX, ignoreCase = true) ->
                Triple(text.substring(MASTER_DNS_PREFIX.length), true, true)
            text.startsWith(COMPACT_PREFIX_V2, ignoreCase = true) ->
                Triple(text.substring(COMPACT_PREFIX_V2.length), true, true)
            text.startsWith(COMPACT_PREFIX, ignoreCase = true) ->
                Triple(text.substring(COMPACT_PREFIX.length), true, false)
            text.startsWith(PREFIX, ignoreCase = true) ->
                Triple(text.substring(PREFIX.length), false, false)
            else -> return null
        }
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_LENGTH || !encoded.matches(Regex("[A-Za-z0-9_-]+"))) return null
        val rawPayload = runCatching {
            val bytes = Base64.decode(encoded, BASE64_FLAGS)
            val json = if (compressed) inflate(bytes, if (brandedEngine != null) shareDictionary else null)
                ?: return@runCatching null else bytes
            val decoded = String(json, Charsets.UTF_8)
            if (positional) decodePositional(decoded) else JsonUtil.fromJsonSafe(decoded, Payload::class.java)
        }.getOrNull() ?: return null
        val payload = if (brandedEngine == null) rawPayload else rawPayload.copy(
            advanced = expandContent(rawPayload.advanced, defaults.first) ?: if (rawPayload.advanced == null) null else return null,
            resolvers = expandContent(rawPayload.resolvers, defaults.second) ?: if (rawPayload.resolvers == null) null else return null,
        )
        val domain = payload.domain?.trim()?.takeIf {
            it.isNotEmpty() && it.none { char -> char.isWhitespace() || char == '"' || char == '\\' }
        } ?: return null
        val key = payload.key?.takeIf { it.isNotBlank() } ?: return null
        if (payload.method !in 0..5) return null
        if (payload.engine != null && payload.engine !in setOf("MasterDNS", "StormDNS", MasterDnsBridge.DNSTT)) return null
        if (brandedEngine == MasterDnsBridge.DNSTT && payload.engine != MasterDnsBridge.DNSTT) return null
        if (brandedEngine == MasterDnsBridge.LABEL && payload.engine == MasterDnsBridge.DNSTT) return null
        if (payload.tunnelMode != null && payload.tunnelMode !in setOf("SOCKS5", "SSH")) return null
        if (payload.engine == MasterDnsBridge.DNSTT && payload.tunnelMode == "SSH" &&
            (payload.sshUsername.isNullOrBlank() || payload.sshPassword.isNullOrBlank() || payload.sshHost.isNullOrBlank())) return null
        if (payload.engine == MasterDnsBridge.DNSTT && runCatching {
                DnsTunnelArguments.dnstt("core", payload.transport ?: "udp", payload.resolvers.orEmpty(), key, domain, MasterDnsBridge.PORT)
            }.isFailure) return null
        return ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = payload.remark?.takeIf { it.isNotBlank() } ?: "MasterDNS"
            description = when (payload.engine) {
                MasterDnsBridge.DNSTT -> MasterDnsBridge.DNSTT
                else -> MasterDnsBridge.LABEL
            }
            dnsTunnelTransport = payload.transport
            dnsTunnelMode = payload.tunnelMode
            dnsTunnelSshUsername = payload.sshUsername
            dnsTunnelSshPassword = payload.sshPassword
            dnsTunnelSshHost = payload.sshHost
            dnsTunnelSshPort = payload.sshPort
            dnsTunnelSshKeepaliveSeconds = payload.sshKeepaliveSeconds ?: 6
            server = "127.0.0.1"
            serverPort = if (payload.engine == MasterDnsBridge.DNSTT && payload.tunnelMode == "SSH")
                MasterDnsBridge.SSH_SOCKS_PORT.toString() else MasterDnsBridge.PORT.toString()
            masterDnsDomain = domain
            masterDnsEncryptionKey = key
            masterDnsMethod = payload.method
            masterDnsAdvanced = payload.advanced
            masterDnsResolvers = payload.resolvers
            masterDnsReuseValidResolvers = payload.reuseValidResolvers == true
            psiphonEnabled = payload.psiphon
            psiphonRegion = payload.region
            psiphonMode = payload.mode
            psiphonCdnIps = payload.cdnIps
            psiphonCdnSni = payload.cdnSni
            psiphonCdnSets = payload.cdnSets
        }
    }

    private fun decodePositional(json: String): Payload? = runCatching {
        val values = JsonParser.parseString(json).asJsonArray
        if (values.size() != 20 && values.size() != 21) return null
        fun string(index: Int): String? = values[index].takeUnless { it.isJsonNull }?.asString
        fun int(index: Int): Int? = values[index].takeUnless { it.isJsonNull }?.asInt
        fun bool(index: Int): Boolean? = values[index].takeUnless { it.isJsonNull }?.asBoolean
        Payload(
            remark = string(0).orEmpty(), engine = string(1), transport = string(2),
            tunnelMode = string(3), sshUsername = string(4), sshPassword = string(5),
            sshHost = string(6), sshPort = int(7), domain = string(8).orEmpty(),
            key = string(9).orEmpty(), method = int(10) ?: -1,
            advanced = string(11), resolvers = string(12), reuseValidResolvers = bool(13),
            psiphon = bool(14) ?: false, region = string(15), mode = string(16),
            cdnIps = string(17), cdnSni = string(18), cdnSets = string(19),
            sshKeepaliveSeconds = if (values.size() == 21) int(20) else null,
        )
    }.getOrNull()

    /** Raw DEFLATE keeps the share payload lossless while avoiding the zlib header bytes. */
    private fun deflate(input: ByteArray, dictionary: ByteArray? = null): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            if (dictionary != null) deflater.setDictionary(dictionary)
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(4 * 1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count == 0 && !deflater.finished()) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray, dictionary: ByteArray? = null): ByteArray? {
        val inflater = Inflater(true)
        return try {
            if (dictionary != null) inflater.setDictionary(dictionary)
            inflater.setInput(input)
            val output = ByteArrayOutputStream(minOf(input.size * 3, MAX_DECOMPRESSED_LENGTH))
            val buffer = ByteArray(4 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    if (output.size() + count > MAX_DECOMPRESSED_LENGTH) return null
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    return null
                }
            }
            output.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}

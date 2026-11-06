package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Compressed, versioned PingNG links for Soar DNS-tunnel profiles. */
object SoarShareCodec {
    const val PREFIX = "PingNG-SoarDNS://"
    const val LEGACY_PREFIX = "PingNG-Soar://"
    private const val MAX_ENCODED_LENGTH = 100_000
    private const val MAX_DECOMPRESSED_LENGTH = 64 * 1024
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    private data class Payload(
        val version: Int = 1,
        val remark: String,
        val zone: String,
        val serverKey: String,
        val resolvers: String? = null,
        val discoveryEnabled: Boolean = true,
        val discoveryCountry: String? = null,
        val tcp: String = "auto",
        val advanced: String? = null,
        val psiphon: Boolean = false,
        val region: String? = null,
        val mode: String? = null,
        val cdnIps: String? = null,
        val cdnSni: String? = null,
        val cdnSets: String? = null,
    )

    fun encode(profile: ProfileItem): String {
        require(SoarBridge.isProfile(profile))
        val useDiscovery = SoarSettings.effectiveDiscoveryEnabled(profile.soarResolvers, profile.soarDiscoveryEnabled)
        val payload = Payload(
            remark = profile.remarks.ifBlank { "SoarDNS" },
            zone = profile.soarZone.orEmpty(),
            serverKey = profile.soarServerKey.orEmpty(),
            resolvers = profile.soarResolvers?.takeIf { it.isNotBlank() },
            discoveryEnabled = useDiscovery,
            discoveryCountry = SoarSettings.storedDiscoveryCountry(profile.soarResolvers, profile.soarDiscoveryCountry),
            tcp = profile.soarTcpMode.orEmpty().ifBlank { "auto" },
            advanced = profile.soarAdvanced,
            psiphon = profile.psiphonEnabled,
            region = profile.psiphonRegion,
            mode = profile.psiphonMode,
            cdnIps = profile.psiphonCdnIps,
            cdnSni = profile.psiphonCdnSni,
            cdnSets = profile.psiphonCdnSets,
        )
        val raw = JsonUtil.toJson(payload).toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_DECOMPRESSED_LENGTH) { "SoarDNS profile is too large to share" }
        val compressed = ByteArrayOutputStream().use { bytes ->
            DeflaterOutputStream(bytes).use { it.write(raw) }
            bytes.toByteArray()
        }
        val encoded = encoder.encodeToString(compressed)
        require(encoded.length <= MAX_ENCODED_LENGTH) { "SoarDNS profile is too large to share" }
        // Keep the payload in the URI path so link handlers do not lowercase it.
        return PREFIX + "p/" + encoded
    }

    fun decode(link: String?): ProfileItem? = runCatching {
        val text = link?.trim().orEmpty()
        if (text.length > MAX_ENCODED_LENGTH + 64) return null
        val prefix = when {
            text.startsWith(PREFIX, ignoreCase = true) -> PREFIX
            text.startsWith(LEGACY_PREFIX, ignoreCase = true) -> LEGACY_PREFIX
            else -> return null
        }
        val suffix = text.substring(prefix.length)
        val encoded = if (suffix.startsWith("p/", ignoreCase = true)) suffix.substring(2) else suffix
        if (encoded.isBlank() || encoded.length > MAX_ENCODED_LENGTH) return null
        val compressed = decoder.decode(encoded)
        val raw = InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2_048)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_DECOMPRESSED_LENGTH) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val payload = JsonUtil.fromJsonSafe(String(raw, Charsets.UTF_8), Payload::class.java) ?: return null
        if (payload.version != 1 || !isValidZone(payload.zone) || !isValidPublicKey(payload.serverKey)) return null
        if (payload.tcp !in setOf("auto", "on", "only", "off")) return null
        val resolvers = payload.resolvers?.let(SoarSettings::normalizeResolvers)
        val discoveryEnabled = SoarSettings.effectiveDiscoveryEnabled(resolvers, payload.discoveryEnabled)
        if (resolvers.isNullOrBlank() && !discoveryEnabled) return null
        val selectedCountry = SoarSettings.selectedDiscoveryCountry(resolvers, payload.discoveryCountry)
        if (selectedCountry != "Auto" && selectedCountry.isNotBlank() && !selectedCountry.matches(Regex("[A-Za-z]{2}"))) return null
        val advanced = SoarSettings.decode(payload.advanced)
        if (SoarSettings.validationError(advanced, payload.tcp, validateDiscovery = discoveryEnabled) != null) return null
        if (SoarSettings.resolverLines(resolvers.orEmpty()).any { !SoarSettings.isValidResolver(it) }) return null
        if (!payload.region.isNullOrBlank() && !payload.region.matches(Regex("(?i)(ANY|[A-Z]{2})"))) return null
        if (!payload.mode.isNullOrBlank() && payload.mode.lowercase(Locale.ROOT) !in setOf("auto", "cdn", "direct")) return null
        ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = payload.remark.ifBlank { "SoarDNS" }
            description = SoarBridge.LABEL
            server = "127.0.0.1"
            serverPort = SoarBridge.PORT.toString()
            soarZone = payload.zone
            soarServerKey = payload.serverKey
            soarResolvers = resolvers
            soarDiscoveryEnabled = discoveryEnabled
            soarDiscoveryCountry = SoarSettings.storedDiscoveryCountry(resolvers, selectedCountry)
            soarTcpMode = payload.tcp
            soarAdvanced = SoarSettings.encode(advanced)
            psiphonEnabled = payload.psiphon
            torEnabled = false
            psiphonRegion = payload.region?.uppercase(Locale.ROOT)?.ifBlank { "ANY" } ?: "ANY"
            psiphonMode = payload.mode?.lowercase(Locale.ROOT)?.ifBlank { "auto" } ?: "auto"
            psiphonCdnIps = payload.cdnIps
            psiphonCdnSni = payload.cdnSni
            psiphonCdnSets = payload.cdnSets
        }
    }.getOrNull()

    private fun isValidZone(value: String): Boolean {
        val zone = value.trim().trimEnd('.')
        if (zone.length !in 3..253 || !zone.contains('.')) return false
        return zone.split('.').all { label ->
            label.length in 1..63 && label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
        }
    }

    private fun isValidPublicKey(value: String): Boolean {
        val text = value.trim()
        if (text.matches(Regex("[A-Fa-f0-9]{64}"))) return true
        val bytes = runCatching { Base64.getDecoder().decode(text) }
            .recoverCatching { Base64.getUrlDecoder().decode(text) }
            .getOrNull()
        return bytes?.size == 32
    }
}

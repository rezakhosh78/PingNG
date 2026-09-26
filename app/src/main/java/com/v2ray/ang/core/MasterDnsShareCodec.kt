package com.v2ray.ang.core

import android.util.Base64
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil

/** Round-trip format for the MasterDNS profile, including its private key and advanced settings. */
object MasterDnsShareCodec {
    const val PREFIX = "pingng-masterdns://profile?v=1&data="
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    private data class Payload(
        val remark: String,
        val domain: String,
        val key: String,
        val method: Int,
        val advanced: String?,
        val resolvers: String?,
        val psiphon: Boolean,
        val region: String?,
        val mode: String?,
        val cdnIps: String?,
        val cdnSni: String?,
        val cdnSets: String?,
    )

    fun encode(profile: ProfileItem): String {
        require(MasterDnsBridge.isProfile(profile))
        val payload = Payload(
            remark = profile.remarks,
            domain = profile.masterDnsDomain.orEmpty(),
            key = profile.masterDnsEncryptionKey.orEmpty(),
            method = profile.masterDnsMethod ?: 1,
            advanced = profile.masterDnsAdvanced,
            resolvers = profile.masterDnsResolvers,
            psiphon = profile.psiphonEnabled,
            region = profile.psiphonRegion,
            mode = profile.psiphonMode,
            cdnIps = profile.psiphonCdnIps,
            cdnSni = profile.psiphonCdnSni,
            cdnSets = profile.psiphonCdnSets,
        )
        val encoded = Base64.encodeToString(JsonUtil.toJson(payload).toByteArray(Charsets.UTF_8), BASE64_FLAGS)
        return PREFIX + encoded
    }

    fun decode(link: String): ProfileItem? {
        val text = link.trim()
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val encoded = text.substring(PREFIX.length)
        if (encoded.isEmpty() || encoded.length > 500_000 || !encoded.matches(Regex("[A-Za-z0-9_-]+"))) return null
        val payload = runCatching {
            val bytes = Base64.decode(encoded, BASE64_FLAGS)
            JsonUtil.fromJsonSafe(String(bytes, Charsets.UTF_8), Payload::class.java)
        }.getOrNull() ?: return null
        val domain = payload.domain?.trim()?.takeIf {
            it.isNotEmpty() && it.none { char -> char.isWhitespace() || char == '"' || char == '\\' }
        } ?: return null
        val key = payload.key?.takeIf { it.isNotBlank() } ?: return null
        if (payload.method !in 0..5) return null
        return ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = payload.remark?.takeIf { it.isNotBlank() } ?: "MasterDNS"
            description = MasterDnsBridge.LABEL
            server = "127.0.0.1"
            serverPort = MasterDnsBridge.PORT.toString()
            masterDnsDomain = domain
            masterDnsEncryptionKey = key
            masterDnsMethod = payload.method
            masterDnsAdvanced = payload.advanced
            masterDnsResolvers = payload.resolvers
            psiphonEnabled = payload.psiphon
            psiphonRegion = payload.region
            psiphonMode = payload.mode
            psiphonCdnIps = payload.cdnIps
            psiphonCdnSni = payload.cdnSni
            psiphonCdnSets = payload.cdnSets
        }
    }
}

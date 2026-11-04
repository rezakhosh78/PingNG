package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable, lossless export format for complete MasterDNS and SoarDNS profiles. */
object DnsFullConfigCodec {
    private const val FORMAT = "PingNG DNS Full Config"
    private const val VERSION = 1
    private const val MAX_DOCUMENT_LENGTH = 2 * 1024 * 1024

    private data class Document(
        val format: String,
        val version: Int,
        val protocol: String,
        val domain: String,
        val profile: ProfileItem,
    )

    fun isExportableProfile(profile: ProfileItem): Boolean =
        profile.configType == EConfigType.SOCKS &&
            protocolOf(profile) != null && domainOf(profile).isNotBlank()

    fun encode(profile: ProfileItem): String {
        require(profile.configType == EConfigType.SOCKS) { "Full DNS export requires a SOCKS profile" }
        val protocol = protocolOf(profile)
            ?: error("Only MasterDNS and SoarDNS profiles can be exported as full config")
        val domain = domainOf(profile).trim().trimEnd('.')
        require(domain.isNotBlank()) { "Profile domain is empty" }
        val document = Document(FORMAT, VERSION, protocol, domain, normalizeSoarDiscovery(profile))
        val text = JsonUtil.toJsonPretty(document) ?: error("Could not serialize profile")
        require(text.length <= MAX_DOCUMENT_LENGTH) { "Profile is too large to export" }
        return "$text\n"
    }

    /** Returns null when the file is malformed or contains a different DNS protocol. */
    fun decode(text: String, expectedProtocol: String? = null): ProfileItem? = runCatching {
        require(text.length <= MAX_DOCUMENT_LENGTH) { "Profile file is too large" }
        val cleanText = text.removePrefix("\uFEFF").trim()
        val document = JsonUtil.fromJsonSafe(cleanText, Document::class.java) ?: return null
        if (document.format != FORMAT || document.version != VERSION) return null
        val profile = document.profile
        val protocol = protocolOf(profile) ?: return null
        if (profile.configType != EConfigType.SOCKS || protocol != document.protocol) return null
        if (!expectedProtocol.isNullOrBlank() &&
            !protocol.equals(expectedProtocol, ignoreCase = true)
        ) return null
        val domain = domainOf(profile).trim().trimEnd('.')
        if (domain.isBlank() || !domain.equals(document.domain.trim().trimEnd('.'), ignoreCase = true)) {
            return null
        }
        normalizeSoarDiscovery(profile)
    }.getOrNull()

    fun suggestedFileName(profile: ProfileItem, date: Date = Date()): String {
        val protocol = protocolOf(profile)
            ?: error("Only MasterDNS and SoarDNS profiles can be exported as full config")
        val domain = domainOf(profile).trim().trimEnd('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "unknown-domain" }
        val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date)
        return "${protocol}_${domain}_${dateText}.txt"
    }

    private fun protocolOf(profile: ProfileItem): String? = when {
        SoarBridge.isProfile(profile) -> SoarBridge.LABEL
        profile.description?.equals(MasterDnsBridge.LABEL, ignoreCase = true) == true ||
            profile.description?.equals("StormDNS", ignoreCase = true) == true -> MasterDnsBridge.LABEL
        else -> null
    }

    private fun domainOf(profile: ProfileItem): String =
        if (SoarBridge.isProfile(profile)) profile.soarZone.orEmpty()
        else profile.masterDnsDomain.orEmpty()

    private fun normalizeSoarDiscovery(profile: ProfileItem): ProfileItem {
        if (!SoarBridge.isProfile(profile) || SoarSettings.resolverLines(profile.soarResolvers.orEmpty()).isEmpty()) {
            return profile
        }
        return profile.copy(soarDiscoveryEnabled = false, soarDiscoveryCountry = null)
    }
}

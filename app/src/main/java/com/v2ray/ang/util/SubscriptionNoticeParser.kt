package com.v2ray.ang.util

import android.net.Uri
import android.util.Base64
import com.v2ray.ang.dto.UrlContentResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Extracts optional provider notices without treating normal subscription lines as notices. */
object SubscriptionNoticeParser {

    private val headerNames = setOf(
        "notice",
        "x-notice",
        "subscription-notice",
        "x-subscription-notice",
        "profile-notice",
        "profile-announce",
        "subscription-message",
        "x-subscription-message",
        "announce",
        "x-announce",
        "announcement",
        "x-announcement",
    )
    private val titleHeaderNames = setOf(
        "subscription-title",
        "x-subscription-title",
        "profile-title",
        "x-profile-title",
        "subscription-name",
        "x-subscription-name",
    )
    private val supportUrlHeaderNames = listOf(
        "support-url",
        "x-support-url",
        "subscription-support-url",
        "x-subscription-support-url",
        "profile-support-url",
        "x-profile-support-url",
        "support_url",
        "profile_web_page_url",
        "profile-web-page-url",
        "x-profile-web-page-url",
        "telegram",
        "x-telegram",
    )
    private val trafficHeaderNames = setOf(
        "subscription-userinfo",
        "x-subscription-userinfo",
        "subscription-user-info",
        "x-subscription-user-info",
        "subscription_userinfo",
        "subscription-info",
        "x-subscription-info",
    )

    private val structuredKeys = "announce|notice|announcement|subscription-notice"
    private val base64Shape = Regex("[A-Za-z0-9_+/=-]+")
    private val base64Token = Regex(
        "(?<![A-Za-z0-9_+/-])([A-Za-z0-9_+/-]{24,}={0,2})(?![A-Za-z0-9_+/-])"
    )
    private val embeddedSupportUrl = Regex(
        "(?im)^\\s*(?:#|//|;)?\\s*(?:support[-_]url|profile[-_]web[-_]page[-_]url)\\s*:\\s*[\"']?([^\\s\"']+)"
    )
    private val trafficFields = Regex(
        "(?i)[\\\"']?(upload|download|total|totl|used|remaining|expire|expiry|expiration|expires)(?:[-_]?bytes)?[\\\"']?\\s*[=:]\\s*[\\\"']?(\\d+)"
    )
    private val requestFields = Regex(
        "(?i)[\\\"']?(requests?[-_]?(?:total|used|remaining|count|limit)|(?:total|used|remaining)[-_]requests?|request[-_]?(?:total|used|remaining|count|limit)|requests?|quota|limit|total|used|remaining)[\\\"']?\\s*[=:]\\s*[\\\"']?(\\d+)"
    )

    fun extract(response: UrlContentResponse): String? {
        val headerNotice = response.headers.entries.asSequence()
            .filter { it.key.trim().lowercase() in headerNames }
            .flatMap { it.value.asSequence() }
            .mapNotNull { it.decodeNoticeValue() }
            .firstOrNull()
        if (!headerNotice.isNullOrBlank()) return headerNotice

        val body = response.content
        if (body.isBlank()) return null

        // Providers commonly expose this metadata as JSON/YAML, as a Clash-style
        // comment header (#announce: ...), or as a small HTML meta tag. Search the
        // raw body first and then a base64-decoded copy when the whole payload is
        // encoded.
        sequenceOf(body, decodeWholePayload(body))
            .filterNotNull()
            .forEach { candidate ->
                val jsonNotice = Regex(
                    "\\\"(?:$structuredKeys)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                    RegexOption.IGNORE_CASE
                ).find(candidate)?.groupValues?.getOrNull(1)?.decodeNoticeValue()
                if (!jsonNotice.isNullOrBlank()) return jsonNotice

                val yamlNotice = Regex(
                    "(?im)^\\s*(?:#|//|;)?\\s*(?:$structuredKeys)\\s*:\\s*[\\\"']?(.+?)[\\\"']?\\s*$"
                ).find(candidate)?.groupValues?.getOrNull(1)?.decodeNoticeValue()
                if (!yamlNotice.isNullOrBlank()) return yamlNotice

                val htmlNotice = Regex(
                    "(?is)<meta[^>]+(?:name|property)\\s*=\\s*[\\\"'](?:$structuredKeys)[\\\"'][^>]+content\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"
                ).find(candidate)?.groupValues?.getOrNull(1)?.decodeNoticeValue()
                if (!htmlNotice.isNullOrBlank()) return htmlNotice
            }
        return null
    }

    /**
     * Reads the provider's optional subscription title. A missing title is
     * intentionally represented as null so the locally entered remark stays
     * unchanged, which is the legacy behavior.
     */
    fun extractSubscriptionTitle(response: UrlContentResponse): String? {
        return response.headers.entries.asSequence()
            .filter { it.key.trim().lowercase() in titleHeaderNames }
            .flatMap { it.value.asSequence() }
            .mapNotNull { it.decodeNoticeValue() }
            .firstOrNull { it.isNotBlank() }
            ?.take(200)
    }

    /** Reads an optional support/community URL advertised by the provider. */
    fun extractSupportUrl(response: UrlContentResponse): String? {
        val headerUrl = supportUrlHeaderNames.asSequence()
            .flatMap { headerName ->
                response.headers.entries.asSequence()
                    .filter { it.key.trim().lowercase() == headerName }
                    .flatMap { it.value.asSequence() }
            }
            .mapNotNull { it.decodeNoticeValue() }
            .mapNotNull { value ->
                Regex("(?i)(?:https?|tg)://[^\\s,;]+")
                    .find(value.trim().trim('"', '\''))
                    ?.value
            }
            .firstOrNull()
            ?.take(1000)
        if (!headerUrl.isNullOrBlank()) return headerUrl

        return bodyCandidates(response.content)
            .mapNotNull { candidate ->
                embeddedSupportUrl.find(candidate)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { it.decodeNoticeValue() }
            }
            .mapNotNull { value ->
                Regex("(?i)(?:https?|tg)://[^\\s,;]+")
                    .find(value.trim().trim('"', '\''))
                    ?.value
            }
            .firstOrNull()
            ?.take(1000)
    }

    data class TrafficUsage(
        val totalBytes: Long,
        val usedBytes: Long,
        val expirationEpochSeconds: Long = -1L,
        val totalRequests: Long = -1L,
        val usedRequests: Long = -1L,
    )

    /** Parses the standard upload/download/total response header. */
    fun extractTrafficUsage(response: UrlContentResponse, subscriptionUrl: String? = null): TrafficUsage? {
        val headerValues = response.headers.entries.asSequence()
            .filter { entry ->
                val name = entry.key.trim().lowercase()
                name in trafficHeaderNames ||
                    name.contains("subscription-userinfo") ||
                    (isWorkerSubscription(subscriptionUrl) &&
                        (name.contains("rate-limit") || name.contains("ratelimit") ||
                            name.contains("request") || name.contains("quota")))
            }
            .flatMap { entry ->
                entry.value.asSequence().map { value ->
                    "${entry.key.trim().lowercase()}=${value.trim()}"
                }
            }
            .filter { it.isNotBlank() }
            .toList()

        headerValues.asSequence().mapNotNull(::parseTrafficUsage).firstOrNull()?.let { return it }
        if (isWorkerSubscription(subscriptionUrl)) {
            parseWorkerUsage(headerValues.joinToString(";")).let { if (it != null) return it }
        }

        bodyCandidates(response.content).forEach { candidate ->
            Regex("(?im)^\\s*(?:#|//|;)?\\s*subscription[-_]user[-_]?info\\s*:\\s*(.+)$")
                .find(candidate)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::parseTrafficUsage)
                ?.let { return it }

            if (isWorkerSubscription(subscriptionUrl)) {
                parseWorkerUsage(candidate)?.let { return it }
            }
        }
        return null
    }

    private fun parseTrafficUsage(raw: String): TrafficUsage? {
        val values = trafficFields
            .findAll(raw)
            .associate { match ->
                val key = match.groupValues[1].lowercase().let { if (it == "totl") "total" else it }
                key to match.groupValues[2].toLongOrNull()
            }
        val total = values["total"]
        // Subscription-Userinfo uses total=0 for unlimited accounts. Keep that
        // value instead of dropping the metadata, so the UI can show used/∞.
        val used = values["used"] ?: run {
            val upload = values["upload"] ?: 0L
            val download = values["download"] ?: 0L
            upload.saturatingAdd(download)
        }
        val expire = values["expire"] ?: values["expiry"] ?: values["expiration"] ?: values["expires"] ?: -1L
        if (total == null && expire < 0L) return null
        if (total != null && total < 0L) return null
        return TrafficUsage(
            totalBytes = total ?: -1L,
            usedBytes = when {
                total == null -> -1L
                total == 0L -> used.coerceAtLeast(0L)
                else -> used.coerceIn(0L, total)
            },
            expirationEpochSeconds = normalizeEpochSeconds(expire),
        )
    }

    private fun parseWorkerUsage(raw: String): TrafficUsage? {
        val matches = requestFields.findAll(raw).toList()
        if (matches.isEmpty()) return null
        val values = matches.associate { match ->
            val key = match.groupValues[1].lowercase()
                .replace('-', '_')
                .replace('.', '_')
            key to match.groupValues[2].toLongOrNull()
        }
        fun value(vararg names: String): Long? = names.asSequence()
            .mapNotNull { wanted -> values.entries.firstOrNull { it.key == wanted }?.value }
            .firstOrNull()

        val total = value(
            "requests_total", "request_total", "total_requests", "request_limit",
            "requests_limit", "requests_total", "requeststotal", "requestslimit",
            "totalrequests", "requestlimit", "limit", "quota", "total"
        ) ?: return null
        if (total <= 0L) return null
        val used = value(
            "requests_used", "request_used", "used_requests", "request_count",
            "requestsused", "requestused", "usedrequests", "requestcount",
            "requests", "request", "used"
        ) ?: value(
            "remaining_requests", "requests_remaining", "request_remaining",
            "requestsremaining", "remainingrequests", "requestremaining", "remaining"
        )?.let { remaining ->
            (total - remaining).coerceAtLeast(0L)
        } ?: return null
        return TrafficUsage(
            totalBytes = -1L,
            usedBytes = -1L,
            expirationEpochSeconds = -1L,
            totalRequests = total,
            usedRequests = used.coerceIn(0L, total),
        )
    }

    /** Matches both Cloudflare's worker.dev and workers.dev subscription hosts. */
    fun isWorkerSubscription(url: String?): Boolean {
        val host = runCatching { Uri.parse(url.orEmpty()).host?.lowercase() }.getOrNull()
            ?: return false
        return host == "worker.dev" || host.endsWith(".worker.dev") ||
            host == "workers.dev" || host.endsWith(".workers.dev")
    }

    private fun normalizeEpochSeconds(value: Long): Long {
        if (value < 0L) return -1L
        if (value == 0L) return 0L
        return if (value > 100_000_000_000L) value / 1000L else value
    }

    private fun bodyCandidates(body: String): Sequence<String> {
        return sequenceOf(body, decodeWholePayload(body))
            .filterNotNull()
            .filter { it.isNotBlank() }
    }

    private fun String.decodeNoticeValue(): String? {
        var value = trim().trim('"', '\'')
        if (value.isEmpty()) return null

        if (value.startsWith("base64:", ignoreCase = true)) {
            value = decodeBase64(value.substringAfter(':')) ?: return null
        } else {
            // Some providers concatenate a readable notice and an encoded
            // metadata/config tail. Decode only valid Base64-looking tokens;
            // never expose the token itself in the notice banner.
            val compact = value.filterNot(Char::isWhitespace)
            if (compact.length >= 24 && base64Shape.matches(compact)) {
                val wholeDecoded = decodeBase64(compact)
                if (wholeDecoded != null && isReadableNotice(wholeDecoded)) {
                    value = wholeDecoded
                } else {
                    return null
                }
            } else {
                value = base64Token.replace(value) { match ->
                    val candidate = decodeBase64(match.groupValues[1])
                    if (candidate != null && isReadableNotice(candidate)) candidate else ""
                }
            }
        }

        if ('%' in value) {
            value = runCatching { Uri.decode(value) ?: value }.getOrDefault(value)
        }

        return value
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\r", "")
            .trim()
            .substringBefore('\uFFFD')
            .trim()
            .takeIf { isReadableNotice(it) }
            ?.take(4000)
    }

    private fun decodeWholePayload(body: String): String? {
        val compact = body.filterNot(Char::isWhitespace)
        if (compact.length < 24 || !compact.matches(Regex("[A-Za-z0-9_+/=-]+"))) return null
        return decodeBase64(compact)
    }

    private fun decodeBase64(value: String): String? {
        val normalized = value.filterNot(Char::isWhitespace)
        if (normalized.isEmpty() || !base64Shape.matches(normalized)) return null
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = sequenceOf(
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
        ).mapNotNull { flags ->
            runCatching { Base64.decode(padded, flags) }.getOrNull()
        }.firstOrNull() ?: return null

        return runCatching {
            Charsets.UTF_8.newDecoder().apply {
                onMalformedInput(CodingErrorAction.REPORT)
                onUnmappableCharacter(CodingErrorAction.REPORT)
            }.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun isReadableNotice(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty() || '\uFFFD' in text) return false

        val invalidControls = text.count { it.isISOControl() && it != '\n' && it != '\t' }
        if (invalidControls > 0) return false

        val meaningful = text.count { !it.isWhitespace() }
        if (meaningful < 2) return false

        // Persian, Latin, CJK, punctuation, and emoji are all valid notice
        // characters. This only rejects binary-like decoded payloads.
        val printable = text.count { !it.isISOControl() }
        return printable.toDouble() / text.length >= 0.8
    }

    private fun Long.saturatingAdd(other: Long): Long {
        return if (Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other
    }
}

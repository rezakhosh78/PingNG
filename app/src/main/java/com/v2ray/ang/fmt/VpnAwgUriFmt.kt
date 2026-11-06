package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Decodes the vpn:// WireGuard INI links included in some subscription responses. */
object VpnAwgUriFmt {
    private const val prefix = "vpn://"
    private const val maxEncodedLength = 1_400_000

    fun parse(uri: String): ProfileItem? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
        val encoded = trimmed.substring(prefix.length).substringBefore('#')
        if (encoded.isBlank() || encoded.length > maxEncodedLength) return null
        return runCatching {
            // URLDecoder treats '+' as a space; preserve '+' in standard Base64 links.
            val payload = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8.name())
                .filterNot(Char::isWhitespace)
            val bytes = runCatching { Base64.getDecoder().decode(payload) }
                .getOrElse { Base64.getUrlDecoder().decode(payload) }
            val config = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
            AmneziaWgFmt.parse(config, forceAmneziaWg = true)
        }.getOrNull()
    }
}

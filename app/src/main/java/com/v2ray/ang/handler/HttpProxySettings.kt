package com.v2ray.ang.handler

/** Validated upstream HTTP CONNECT proxy settings used by the app and Xray. */
data class HttpProxySettings(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    /** URL form consumed by Psiphon's UpstreamProxyUrl setting. */
    fun asHttpUrl(): String = buildString {
        append("http://")
        val encodedUsername = username?.takeIf { it.isNotEmpty() }?.let(::encodeUserInfo)
        if (encodedUsername != null) {
            append(encodedUsername)
            password?.let {
                append(':')
                append(encodeUserInfo(it))
            }
            append('@')
        }
        val urlHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        append(urlHost)
        append(':')
        append(port)
    }

    private fun encodeUserInfo(value: String): String = buildString {
        val hex = "0123456789ABCDEF"
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xff
            val safe = (code in 'a'.code..'z'.code) ||
                (code in 'A'.code..'Z'.code) ||
                (code in '0'.code..'9'.code) ||
                code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
            if (safe) {
                append(code.toChar())
            } else {
                append('%')
                append(hex[code ushr 4])
                append(hex[code and 0x0f])
            }
        }
    }

    companion object {
        fun from(
            hostValue: String?,
            portValue: String?,
            usernameValue: String? = null,
            passwordValue: String? = null,
        ): HttpProxySettings? {
            val host = hostValue?.trim()?.removePrefix("[")?.removeSuffix("]")
                ?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) && !it.contains('/') }
                ?: return null
            val port = portValue?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return HttpProxySettings(
                host = host,
                port = port,
                username = usernameValue?.trim()?.takeIf { it.isNotEmpty() },
                password = passwordValue?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

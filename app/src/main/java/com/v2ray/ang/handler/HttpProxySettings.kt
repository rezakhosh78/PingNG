package com.v2ray.ang.handler

/** Validated upstream HTTP CONNECT proxy settings used by the app and Xray. */
data class HttpProxySettings(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
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

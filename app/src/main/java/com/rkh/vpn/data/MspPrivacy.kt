package com.rkh.vpn.data

/** Removes discovered MSP endpoints before any engine message reaches a user-visible log. */
object MspPrivacy {
    private val ipv4Address = Regex(
        "\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}" +
            "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b"
    )

    fun redact(text: String): String = ipv4Address.replace(text, "[hidden-address]")

    fun redactThrowable(throwable: Throwable?): Throwable? {
        if (throwable == null) return null
        return RuntimeException(redact(throwable.message.orEmpty()).ifBlank { throwable.javaClass.simpleName }).apply {
            stackTrace = throwable.stackTrace
        }
    }
}

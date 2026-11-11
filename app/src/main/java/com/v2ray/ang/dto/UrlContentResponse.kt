package com.v2ray.ang.dto

/**
 * Subscription response payload together with the response headers that may contain
 * provider metadata such as a notice.
 */
data class UrlContentResponse(
    val content: String,
    val headers: Map<String, List<String>> = emptyMap(),
)

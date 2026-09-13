package com.v2ray.ang.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    var updateInterval: Long = 1440, // in minutes, default to 24 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    var requestHeaders: String? = null,
    /** Latest notice/announcement advertised by this subscription, if supported. */
    var notice: String? = null,
    /** Optional provider support/community URL advertised in the response headers. */
    var supportUrl: String? = null,
    /** Traffic quota and consumed bytes advertised by Subscription-Userinfo. */
    var trafficTotalBytes: Long = -1,
    var trafficUsedBytes: Long = -1,
    /** Expiration timestamp advertised by the provider, in epoch seconds. */
    var expirationEpochSeconds: Long = -1,
    /** Worker.dev subscriptions may expose request quota instead of byte quota. */
    var trafficTotalRequests: Long = -1,
    var trafficUsedRequests: Long = -1,
)

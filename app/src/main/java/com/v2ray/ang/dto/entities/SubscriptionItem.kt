package com.v2ray.ang.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    /** User alias is independent of the title advertised by the provider. */
    var customRemarks: String? = null,
    var providerRemarks: String? = null,
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
) {
    fun applyProviderTitle(title: String?) {
        title?.takeIf { it.isNotBlank() }?.let { providerRemarks = it }
        remarks = customRemarks?.takeIf { it.isNotBlank() }
            ?: providerRemarks?.takeIf { it.isNotBlank() } ?: remarks
    }

    fun applyUserEdit(name: String, newUrl: String, previousName: String) {
        if (newUrl.isBlank() && url.isNotBlank()) {
            // Removing a real subscription URL turns this entry back into the
            // built-in Default group. A group that already has no URL is the
            // built-in group itself, though, and its user-selected name must
            // remain editable.
            customRemarks = null
            providerRemarks = null
            remarks = "Default"
        } else {
            if (name != previousName) customRemarks = name.takeIf { it.isNotBlank() }
            remarks = customRemarks ?: providerRemarks ?: name.takeIf { it.isNotBlank() } ?: "Default"
        }
        url = newUrl
    }
}

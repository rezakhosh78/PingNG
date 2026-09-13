package com.v2ray.ang.dto

data class GroupMapItem(
    var id: String,
    var remarks: String,
    /** Provider notice shown above this subscription's server list. */
    var notice: String? = null,
    var supportUrl: String? = null,
    var trafficTotalBytes: Long = -1,
    var trafficUsedBytes: Long = -1,
    var expirationEpochSeconds: Long = -1,
    var trafficTotalRequests: Long = -1,
    var trafficUsedRequests: Long = -1,
    /** True only for a real subscription URL; false for Default and All groups. */
    var hasSubscriptionLink: Boolean = false,
    /** Worker subscriptions get a visible quota track even when no quota header is returned. */
    var isWorkerSubscription: Boolean = false,
)

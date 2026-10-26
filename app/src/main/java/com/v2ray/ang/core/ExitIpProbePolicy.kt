package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem

/** ServerLess profiles need exit-IP requests to follow the running core SOCKS route. */
internal fun shouldPreferLiveSocksForServerLessExitIp(
    profile: ProfileItem?,
    subscriptionUrl: String?,
): Boolean {
    if (profile == null) return false
    if (profile.subscriptionId == AppConfig.SERVERLESS_SUBSCRIPTION_ID) return true

    val url = subscriptionUrl?.substringBefore('#')?.trim().orEmpty()
    val canonicalUrl = AppConfig.SERVERLESS_SUBSCRIPTION_URL.substringBefore('#')
    return url.equals(canonicalUrl, ignoreCase = true) ||
        url.contains("/Serverless-for-Iran.json", ignoreCase = true)
}

package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.dto.entities.ProfileItem

/** True when a profile belongs to the requested subscription group and is user-visible. */
internal fun belongsToSubscriptionGroup(profile: ProfileItem, groupId: String): Boolean {
    if (!profile.managedBy.isNullOrBlank()) return false

    return if (groupId == DEFAULT_SUBSCRIPTION_ID) {
        profile.subscriptionId.isBlank() || profile.subscriptionId == DEFAULT_SUBSCRIPTION_ID
    } else {
        profile.subscriptionId == groupId
    }
}

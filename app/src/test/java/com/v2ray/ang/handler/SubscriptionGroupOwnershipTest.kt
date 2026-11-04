package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubscriptionGroupOwnershipTest {
    @Test
    fun onlyProfilesOwnedByTheRequestedSubscriptionAppearInItsGroup() {
        assertTrue(belongsToSubscriptionGroup(profile("sub-a"), "sub-a"))
        assertFalse(belongsToSubscriptionGroup(profile("sub-b"), "sub-a"))
    }

    @Test
    fun defaultGroupAcceptsLegacyBlankOwnersAndExplicitDefaultOwner() {
        assertTrue(belongsToSubscriptionGroup(profile(""), AppConfig.DEFAULT_SUBSCRIPTION_ID))
        assertTrue(
            belongsToSubscriptionGroup(
                profile(AppConfig.DEFAULT_SUBSCRIPTION_ID),
                AppConfig.DEFAULT_SUBSCRIPTION_ID,
            )
        )
        assertFalse(belongsToSubscriptionGroup(profile("sub-a"), AppConfig.DEFAULT_SUBSCRIPTION_ID))
    }

    @Test
    fun managedCompositeChildrenStayHidden() {
        val child = profile("sub-a").apply { managedBy = "parent-guid" }

        assertFalse(belongsToSubscriptionGroup(child, "sub-a"))
    }

    private fun profile(subscriptionId: String) = ProfileItem.create(EConfigType.VMESS).apply {
        this.subscriptionId = subscriptionId
    }
}

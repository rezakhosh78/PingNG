package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExitIpProbePolicyTest {
    @Test
    fun serverLessProfilesUseTheLiveSocksRouteForExitIpChecks() {
        val defaultGroupProfile = ProfileItem(
            configType = EConfigType.VLESS,
            subscriptionId = AppConfig.SERVERLESS_SUBSCRIPTION_ID,
        )
        val alternateIdProfile = defaultGroupProfile.copy(subscriptionId = "custom-serverless-group")
        val unrelatedProfile = defaultGroupProfile.copy(subscriptionId = "other-group")

        assertTrue(shouldPreferLiveSocksForServerLessExitIp(defaultGroupProfile, null))
        assertTrue(
            shouldPreferLiveSocksForServerLessExitIp(
                alternateIdProfile,
                "https://raw.githubusercontent.com/patterniha/Serverless-for-Iran/refs/heads/main/Subscription/Serverless-for-Iran.json#ServerLess",
            ),
        )
        assertFalse(shouldPreferLiveSocksForServerLessExitIp(unrelatedProfile, "https://example.com/nodes.json"))
        assertFalse(shouldPreferLiveSocksForServerLessExitIp(null, AppConfig.SERVERLESS_SUBSCRIPTION_URL))
    }
}

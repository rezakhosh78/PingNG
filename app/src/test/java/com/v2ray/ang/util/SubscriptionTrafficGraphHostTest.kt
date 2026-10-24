package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubscriptionTrafficGraphHostTest {
    @Test
    fun workerAndPagesHostsAreRecognizedWithoutMatchingLookalikes() {
        listOf(
            "worker.dev",
            "account.worker.dev",
            "workers.dev",
            "account.workers.dev",
            "project.pages.dev",
            "pages.dev",
        ).forEach { host ->
            assertTrue(SubscriptionNoticeParser.isCloudflareWorkerHost(host), host)
        }

        listOf("pages.dev.example.com", "notworker.dev", "example.com").forEach { host ->
            assertFalse(SubscriptionNoticeParser.isCloudflareWorkerHost(host), host)
        }
    }

    @Test
    fun bundledServerLessUrlIsEligibleForLocalTrafficGraph() {
        assertTrue(
            SubscriptionNoticeParser.isLocalTrafficGraphSubscription(AppConfig.SERVERLESS_SUBSCRIPTION_URL),
        )
    }
}

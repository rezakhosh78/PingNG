package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WarpMasqueRegistrationExecutionPolicyTest {
    @Test
    fun masqueProxyRegistrationUsesAnIsolatedProxyServiceUnlessProxyOnlyServiceOwnsStartup() {
        assertEquals(
            WarpMasqueRegistrationExecutionPolicy.Execution.TEMPORARY_PROXY_ONLY_SERVICE,
            WarpMasqueRegistrationExecutionPolicy.executionFor(WarpMasqueRegistrationExecutionPolicy.Host.VPN),
        )
        assertEquals(
            WarpMasqueRegistrationExecutionPolicy.Execution.TEMPORARY_PROXY_ONLY_SERVICE,
            WarpMasqueRegistrationExecutionPolicy.executionFor(WarpMasqueRegistrationExecutionPolicy.Host.ROOT),
        )
        assertEquals(
            WarpMasqueRegistrationExecutionPolicy.Execution.CURRENT_SERVICE,
            WarpMasqueRegistrationExecutionPolicy.executionFor(WarpMasqueRegistrationExecutionPolicy.Host.PROXY_ONLY),
        )
        assertEquals(
            WarpMasqueRegistrationExecutionPolicy.Execution.TEMPORARY_PROXY_ONLY_SERVICE,
            WarpMasqueRegistrationExecutionPolicy.executionFor(WarpMasqueRegistrationExecutionPolicy.Host.OTHER),
        )
    }

    @Test
    fun autoRetriesDirectRegistrationFailuresThroughProxy1ButDoesNotRetryCancellation() {
        assertEquals(
            true,
            WarpMasqueRegistrationFallbackPolicy.shouldFallbackToProxy1(
                autoSelected = true,
                directAttemptFailed = true,
                canceled = false,
            ),
        )
        assertEquals(
            false,
            WarpMasqueRegistrationFallbackPolicy.shouldFallbackToProxy1(
                autoSelected = true,
                directAttemptFailed = false,
                canceled = false,
            ),
        )
        assertEquals(
            false,
            WarpMasqueRegistrationFallbackPolicy.shouldFallbackToProxy1(
                autoSelected = true,
                directAttemptFailed = true,
                canceled = true,
            ),
        )
        assertEquals(
            false,
            WarpMasqueRegistrationFallbackPolicy.shouldFallbackToProxy1(
                autoSelected = false,
                directAttemptFailed = true,
                canceled = false,
            ),
        )
    }
}

package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun directAndTunnelSelectionsResolveToCurrentRegistrationRoutes() {
        assertEquals(
            WarpRegistrationProxy.DIRECT,
            WarpRegistrationProxy.normalizeSelection("Auto"),
        )
        assertEquals(
            WarpRegistrationProxy.TUNNEL,
            WarpRegistrationProxy.normalizeSelection(WarpRegistrationProxy.TUNNEL_LABEL),
        )
        assertEquals(
            WarpRegistrationProxy.TUNNEL,
            WarpRegistrationProxy.normalizeSelection("__PINGNG_PROXY_1_VLESS__"),
        )
        assertTrue(WarpRegistrationProxy.isDirectChoice(WarpRegistrationProxy.DIRECT_LABEL))
        assertTrue(WarpRegistrationProxy.isTunnelChoice(WarpRegistrationProxy.TUNNEL_LABEL))
        assertFalse(WarpRegistrationProxy.isDirectChoice("saved-proxy-guid"))
        assertFalse(WarpRegistrationProxy.isTunnelChoice("saved-proxy-guid"))
    }
}

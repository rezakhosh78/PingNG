package com.v2ray.ang.core

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpMasqueStartupPolicyTest {
    @Test
    fun masqueRegistrationRunsBeforeSecondLayerBootstrapOnlyForWarpProfiles() {
        assertTrue(WarpMasqueStartupPolicy.shouldRegisterBeforeSecondLayer(EConfigType.WARP, true))
        assertFalse(WarpMasqueStartupPolicy.shouldRegisterBeforeSecondLayer(EConfigType.WARP, false))
        assertFalse(WarpMasqueStartupPolicy.shouldRegisterBeforeSecondLayer(EConfigType.VLESS, true))
        assertFalse(WarpMasqueStartupPolicy.shouldRegisterBeforeSecondLayer(null, true))
    }
}

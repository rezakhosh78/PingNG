package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpEndpointSelectionTest {
    @Test
    fun customModeIsPreserved() {
        assertEquals(
            WarpPlusConfig.ENDPOINT_MODE_CUSTOM,
            WarpPlusConfig.normalizeEndpointTestMode(WarpPlusConfig.ENDPOINT_MODE_CUSTOM),
        )
    }

    @Test
    fun unknownAndLegacyModesFallBackToAutomatic() {
        listOf(null, "", "Fast", "Medium", "Automatic", " custom ").forEach { value ->
            assertEquals(
                WarpPlusConfig.ENDPOINT_MODE_AUTO,
                WarpPlusConfig.normalizeEndpointTestMode(value),
            )
        }
    }

    @Test
    fun currentAndLegacyWarpPlusDescriptionsAreRecognized() {
        assertTrue(WarpPlusConfig.isDescription(WarpPlusConfig.DESCRIPTION))
        assertTrue(WarpPlusConfig.isDescription(WarpPlusConfig.LEGACY_DESCRIPTION))
        assertFalse(WarpPlusConfig.isDescription("WARP WireGuard"))
    }
}

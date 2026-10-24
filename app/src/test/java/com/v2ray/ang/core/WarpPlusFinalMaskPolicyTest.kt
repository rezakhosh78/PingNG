package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpPlusFinalMaskPolicyTest {
    private val parent = ProfileItem.create(EConfigType.PROXYCHAIN).apply {
        description = WarpPlusConfig.DESCRIPTION
        warpFinalMaskEnabled = true
    }

    @Test
    fun automaticModePrefersFastMaskAndAppliesItToOuterOutbound() {
        val fastMask = """{"udp":[{"type":"noise","settings":{"reset":"10-20"}}]}"""
        val outerMask = """{"udp":[{"type":"noise","settings":{"reset":"30-60"}}]}"""
        val outer = ProfileItem.create(EConfigType.WIREGUARD).copy(finalMask = outerMask)
        val profile = parent.copy(autoScanEndpoint = true, warpFastFinalMask = fastMask)
        val outbound = V2rayConfig.OutboundBean(protocol = "wireguard")

        assertTrue(WarpPlusFinalMaskPolicy.applyToOuterOutbound(profile, outer, outbound))
        assertEquals(JsonParser.parseString(fastMask), outbound.streamSettings?.finalmask)
        assertNull(outbound.streamSettings?.network)
    }

    @Test
    fun customModeUsesTheOuterChildMaskAndDefaultRemainsEnabled() {
        val customMask = """{"udp":[{"type":"noise","settings":{"reset":"20-40"}}]}"""
        val outer = ProfileItem.create(EConfigType.WIREGUARD).copy(finalMask = customMask)
        val profile = parent.copy(
            autoScanEndpoint = false,
            warpEndpointTestMode = WarpPlusConfig.ENDPOINT_MODE_CUSTOM,
            warpFastFinalMask = """{"udp":[]}""",
        )

        assertEquals(customMask, WarpPlusFinalMaskPolicy.effectiveMask(profile, outer))
        assertTrue(WarpPlusFinalMaskPolicy.shouldShowBadge(profile))
        assertEquals(
            WarpPlusConfig.DEFAULT_FINAL_MASK,
            WarpPlusFinalMaskPolicy.effectiveMask(parent, outer.copy(finalMask = null)),
        )
    }

    @Test
    fun endpointModeSelectsTheMaskEvenWhenEndpointAutoScanIsDisabled() {
        val fastMask = """{"udp":[{"type":"noise","settings":{"reset":"10-20"}}]}"""
        val outerMask = """{"udp":[{"type":"noise","settings":{"reset":"30-60"}}]}"""
        val profile = parent.copy(
            autoScanEndpoint = false,
            warpEndpointTestMode = WarpPlusConfig.ENDPOINT_MODE_AUTO,
            warpFastFinalMask = fastMask,
        )

        assertEquals(fastMask, WarpPlusFinalMaskPolicy.effectiveMask(profile, ProfileItem.create(EConfigType.WIREGUARD).copy(finalMask = outerMask)))
    }

    @Test
    fun malformedStoredMaskFallsBackToTheKnownDefault() {
        val profile = parent.copy(
            warpEndpointTestMode = WarpPlusConfig.ENDPOINT_MODE_AUTO,
            warpFastFinalMask = "not-json",
        )

        assertEquals(
            WarpPlusConfig.DEFAULT_FINAL_MASK,
            WarpPlusFinalMaskPolicy.effectiveMask(profile, ProfileItem.create(EConfigType.WIREGUARD)),
        )
    }

    @Test
    fun disablingFinalMaskClearsTheConvertedOuterOutboundAndHidesBadge() {
        val outer = ProfileItem.create(EConfigType.WIREGUARD).copy(finalMask = WarpPlusConfig.DEFAULT_FINAL_MASK)
        val profile = parent.copy(warpFinalMaskEnabled = false)
        val outbound = V2rayConfig.OutboundBean(
            protocol = "wireguard",
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                finalmask = JsonParser.parseString(WarpPlusConfig.DEFAULT_FINAL_MASK),
            ),
        )

        assertFalse(WarpPlusFinalMaskPolicy.applyToOuterOutbound(profile, outer, outbound))
        assertNull(outbound.streamSettings?.finalmask)
        assertFalse(WarpPlusFinalMaskPolicy.shouldShowBadge(profile))
    }

    @Test
    fun standaloneWireguardBadgeStillTracksItsOwnFinalMask() {
        val profile = ProfileItem.create(EConfigType.WIREGUARD)
        assertFalse(WarpPlusFinalMaskPolicy.shouldShowBadge(profile))
        assertTrue(WarpPlusFinalMaskPolicy.shouldShowBadge(profile.copy(finalMask = WarpPlusConfig.DEFAULT_FINAL_MASK)))
    }
}

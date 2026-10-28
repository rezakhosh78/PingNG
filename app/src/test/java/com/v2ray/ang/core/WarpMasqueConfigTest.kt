package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class WarpMasqueConfigTest {
    @Test
    fun editorUsesTheLastScannedEndpointInsteadOfTheDefaultPrimary() {
        val profile = masqueProfile().apply {
            autoScanEndpoint = true
            warpMasqueEndpointMode = WarpMasqueConfig.ENDPOINT_MODE_AUTO
            warpMasquePrimaryEndpoint = WarpMasqueConfig.DEFAULT_ENDPOINT
            warpMasqueEndpointPort = WarpMasqueConfig.DEFAULT_PORT
            warpMasqueSelectedEndpoint = "162.159.198.187:443"
            server = WarpMasqueConfig.DEFAULT_ENDPOINT
            serverPort = WarpMasqueConfig.DEFAULT_PORT.toString()
        }

        assertEquals(
            WarpMasqueConfig.EditorEndpoint("162.159.198.187", 443),
            WarpMasqueConfig.editorEndpoint(profile),
        )
    }

    @Test
    fun editorKeepsTheScannedEndpointWhenAutoScanWasTurnedOffWithAStaleDefaultPrimary() {
        val profile = masqueProfile().apply {
            autoScanEndpoint = false
            warpMasqueEndpointMode = WarpMasqueConfig.ENDPOINT_MODE_CUSTOM
            warpMasquePrimaryEndpoint = WarpMasqueConfig.DEFAULT_ENDPOINT
            warpMasqueEndpointPort = WarpMasqueConfig.DEFAULT_PORT
            warpMasqueSelectedEndpoint = "162.159.198.187:443"
            server = WarpMasqueConfig.DEFAULT_ENDPOINT
            serverPort = WarpMasqueConfig.DEFAULT_PORT.toString()
        }

        assertEquals(
            WarpMasqueConfig.EditorEndpoint("162.159.198.187", 443),
            WarpMasqueConfig.editorEndpoint(profile),
        )
    }

    @Test
    fun editorPrefersAnExplicitManualEndpointOverAnOlderScanResult() {
        val profile = masqueProfile().apply {
            autoScanEndpoint = false
            warpMasqueEndpointMode = WarpMasqueConfig.ENDPOINT_MODE_CUSTOM
            warpMasquePrimaryEndpoint = "198.51.100.25"
            warpMasqueEndpointPort = 8443
            warpMasqueSelectedEndpoint = "162.159.198.187:443"
            server = "198.51.100.25"
            serverPort = "8443"
        }

        assertEquals(
            WarpMasqueConfig.EditorEndpoint("198.51.100.25", 8443),
            WarpMasqueConfig.editorEndpoint(profile),
        )
    }

    private fun masqueProfile() = ProfileItem.create(EConfigType.WARP).apply {
        description = WarpMasqueConfig.DESCRIPTION
    }
}

package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedtestManagerTest {
    @Test fun cloudflareMetadataIsTriedBeforeTheConfiguredProviderForAwgPsiphon() {
        assertEquals(
            listOf(
                "https://speed.cloudflare.com/meta",
                "https://api.ip.sb/geoip",
            ),
            SpeedtestManager.remoteIpInfoProbeUrls(
                configuredUrl = "https://api.ip.sb/geoip",
                preferCloudflareEndpoint = true,
            ),
        )
    }

    @Test fun configuredIpProviderRemainsUnchangedForOtherProfiles() {
        assertEquals(
            listOf("https://custom.example/ip"),
            SpeedtestManager.remoteIpInfoProbeUrls(
                configuredUrl = " https://custom.example/ip ",
                preferCloudflareEndpoint = false,
            ),
        )
    }

    @Test fun cloudflareClientIpAndCountryAreRecognized() {
        val info = SpeedtestManager.parseRemoteEndpoint(
            """{"clientIp":"104.28.214.161","country":"IR"}""",
        )

        assertEquals("104.28.214.161", info?.ipAddress)
        assertEquals("IR", info?.countryCode)
        assertEquals("IR", info?.country)
    }

    @Test fun nonJsonIpApiResponseIsNotAcceptedAsMetadata() {
        assertNull(SpeedtestManager.parseRemoteEndpoint("104.28.214.161"))
    }
}

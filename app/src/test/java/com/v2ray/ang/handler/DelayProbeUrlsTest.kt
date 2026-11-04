package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Test

class DelayProbeUrlsTest {
    @Test
    fun configuredUrlsStayFirstAndFallbacksAreUniqueAndValidHttpUrls() {
        assertEquals(
            listOf(
                "https://primary.example/generate_204",
                "https://secondary.example/generate_204",
                "https://cp.cloudflare.com/generate_204",
                "https://connectivitycheck.gstatic.com/generate_204",
                "http://1.1.1.1/cdn-cgi/trace",
                "http://1.0.0.1/cdn-cgi/trace",
            ),
            SpeedtestManager.delayProbeUrls(
                " https://primary.example/generate_204 ",
                "https://secondary.example/generate_204",
                "https://primary.example/generate_204",
                "file:///invalid",
                null,
            ),
        )
    }
}

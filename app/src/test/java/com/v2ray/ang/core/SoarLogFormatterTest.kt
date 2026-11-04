package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoarLogFormatterTest {
    @Test
    fun groupsDiscoveryProgressAndKeepsLatestCounts() {
        val parsed = SoarLogFormatter.parse(
            """10:00:00 SOAR_DNS_SCAN discovery 0 270 0
                |10:00:05 SOAR_DNS_SCAN discovery 130 270 12
                |10:00:10 SOAR_DNS_SCAN discovery 270 270 37
                |10:00:11 client connected""".trimMargin(),
        )

        assertEquals(1, parsed.scans.size)
        assertTrue(parsed.scans.single().discovery)
        assertEquals(270, parsed.scans.single().checked)
        assertEquals(270, parsed.scans.single().total)
        assertEquals(37, parsed.scans.single().verified)
        assertEquals("10:00:10", parsed.scans.single().time)
        assertEquals("10:00:11 client connected", parsed.details)
    }

    @Test
    fun supportsLegacyProgressAndSeparatesNewScanRuns() {
        val parsed = SoarLogFormatter.parse(
            """06:00:00 SOAR_DNS_SCAN 270 270 37
                |06:10:00 SOAR_DNS_SCAN manual 2 4 1
                |06:11:00 SOAR_DNS_SCAN manual 0 4 0""".trimMargin(),
        )

        assertEquals(3, parsed.scans.size)
        assertTrue(parsed.scans[0].discovery)
        assertTrue(!parsed.scans[1].discovery)
        assertEquals(0, parsed.scans[2].checked)
        assertTrue(parsed.details.isEmpty())
    }
}

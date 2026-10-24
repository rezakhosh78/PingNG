package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoarSettingsTest {

    @Test
    fun resolverAddressesWithoutPortsUseDnsPort53() {
        val normalized = SoarSettings.normalizeResolvers(
            "87.107.6.2\n8.8.8.8:5353\n2001:4860:4860::8888\n[2606:4700:4700::1111]",
        )

        assertEquals(
            "87.107.6.2:53\n8.8.8.8:5353\n[2001:4860:4860::8888]:53\n[2606:4700:4700::1111]:53",
            normalized,
        )
        assertTrue(SoarSettings.resolverLines("87.107.6.2").all(SoarSettings::isValidResolver))
    }

    @Test
    fun answerSizeMustUseTheSupported48ByteSteps() {
        assertEquals(null, SoarSettings.validationError(SoarAdvancedSettings(maxAnswer = 1232), "auto"))
        assertFalse(SoarSettings.validationError(SoarAdvancedSettings(maxAnswer = 1200), "auto").isNullOrBlank())
    }

    @Test
    fun manualResolversDisableDiscoveryAndIgnoreTheCountrySelector() {
        assertFalse(SoarSettings.effectiveDiscoveryEnabled("8.8.8.8", true))
        assertEquals(null, SoarSettings.effectiveDiscoveryCountry("8.8.8.8", "IR"))
    }

    @Test
    fun emptyResolverListKeepsOptionalDiscoveryAndCountrySettings() {
        assertTrue(SoarSettings.effectiveDiscoveryEnabled("", true))
        assertTrue(SoarSettings.effectiveDiscoveryEnabled(null, null))
        assertEquals("IR", SoarSettings.effectiveDiscoveryCountry("", " ir "))
        assertEquals("IR", SoarSettings.effectiveDiscoveryCountry("", null))
        assertEquals("IR", SoarSettings.selectedDiscoveryCountry("", null))
        assertEquals("DE", SoarSettings.effectiveDiscoveryCountry("", "de"))
        assertEquals(null, SoarSettings.effectiveDiscoveryCountry("", "Auto"))
        assertFalse(SoarSettings.effectiveDiscoveryEnabled("", false))
    }

    @Test
    fun manualResolversDisableDiscoveryAndRetainOnlyTheManualResolverPlan() {
        assertFalse(SoarSettings.effectiveDiscoveryEnabled("8.8.8.8", true))
        assertEquals(null, SoarSettings.effectiveDiscoveryCountry("8.8.8.8", "IR"))
        assertEquals(null, SoarSettings.storedDiscoveryCountry("8.8.8.8", "IR"))
    }

    @Test
    fun discoverySettingsDefaultToFastScanValuesAndMigrateOldDefaults() {
        val defaults = SoarSettings.decode(null)
        assertEquals(1, defaults.discoveryWant)
        assertEquals(128, defaults.discoveryConcurrency)
        assertEquals(1, defaults.discoveryPaceMs)
        assertTrue(defaults.discoveryCacheEnabled)

        val migrated = SoarSettings.decode(
            """{"discoveryWant":16,"discoveryConcurrency":16,"discoveryPaceMs":20,"discoveryCacheEnabled":false}""",
        )
        assertEquals(1, migrated.discoveryWant)
        assertEquals(128, migrated.discoveryConcurrency)
        assertEquals(1, migrated.discoveryPaceMs)
        assertTrue(migrated.discoveryCacheEnabled)
    }

    @Test
    fun manualModeStillValidatesSharedScanSpeedDrafts() {
        assertEquals(
            null,
            SoarSettings.applyNumericDrafts(null, mapOf("discoveryConcurrency" to "")),
        )
        val manualScan = SoarSettings.applyNumericDrafts(
            null,
            mapOf("discoveryConcurrency" to "64"),
            validateDiscovery = false,
        )
        assertEquals(64, SoarSettings.decode(manualScan).discoveryConcurrency)
        assertEquals(
            null,
            SoarSettings.applyNumericDrafts(null, mapOf("discoverySampleRanges" to ""), validateDiscovery = false),
        )
        assertTrue(SoarSettings.validationError(SoarAdvancedSettings(discoveryConcurrency = 0), "auto", validateDiscovery = false) != null)
        assertTrue(SoarSettings.validationError(SoarAdvancedSettings(discoveryConcurrency = 0), "auto", validateDiscovery = true) != null)
    }

    @Test
    fun discoveryFieldsAreAvailableOnTheBasicSettingsScreen() {
        assertEquals(
            listOf("discoveryConcurrency", "discoveryPaceMs", "discoveryWant", "discoveryCacheEnabled"),
            SoarSettings.discoveryFields(null).map { it.key },
        )
        assertTrue(SoarSettings.fields(null).none { it.key in setOf(
            "discoveryConcurrency", "discoveryPaceMs", "discoveryWant", "discoveryCacheEnabled",
        ) })
    }

    @Test
    fun selectedCountryIsStoredAndAutoRemainsAnExplicitChoice() {
        assertEquals("IR", SoarSettings.selectedDiscoveryCountry("", null))
        assertEquals("DE", SoarSettings.selectedDiscoveryCountry("", "de"))
        assertEquals("Auto", SoarSettings.storedDiscoveryCountry("", "Auto"))
        assertEquals(null, SoarSettings.effectiveDiscoveryCountry("", "Auto"))
    }
}

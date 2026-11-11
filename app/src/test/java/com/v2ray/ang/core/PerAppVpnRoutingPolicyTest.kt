package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PerAppVpnRoutingPolicyTest {
    private val self = "com.pingng.android"

    @Test
    fun warpProxyModeAllowsOnlySelectedAppsAndKeepsPingNgOutsideTheTunnel() {
        val rules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = false,
            selectedPackages = setOf("com.telegram.messenger", "com.browser", self),
            servicePackage = self,
            excludeServicePackage = true,
        )

        assertEquals(setOf("com.telegram.messenger", "com.browser"), rules.allowedPackages)
        assertEquals(setOf<String>(), rules.disallowedPackages)
    }

    @Test
    fun warpBypassModeExcludesSelectedAppsAndPingNg() {
        val rules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = true,
            selectedPackages = setOf("com.telegram.messenger", "com.browser"),
            servicePackage = self,
            excludeServicePackage = true,
        )

        assertEquals(setOf<String>(), rules.allowedPackages)
        assertEquals(setOf("com.telegram.messenger", "com.browser", self), rules.disallowedPackages)
    }

    @Test
    fun masterDnsAndSoarProxyModeUseOnlySelectedApps() {
        val masterDnsRules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = false,
            selectedPackages = setOf("com.telegram.messenger", "com.browser", self),
            servicePackage = self,
            excludeServicePackage = true,
        )
        val soarRules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = false,
            selectedPackages = setOf("com.telegram.messenger"),
            servicePackage = self,
            excludeServicePackage = true,
        )

        assertEquals(setOf("com.telegram.messenger", "com.browser"), masterDnsRules.allowedPackages)
        assertEquals(setOf("com.telegram.messenger"), soarRules.allowedPackages)
        assertEquals(setOf<String>(), masterDnsRules.disallowedPackages)
        assertEquals(setOf<String>(), soarRules.disallowedPackages)
    }

    @Test
    fun masterDnsAndSoarBypassModeLeaveOnlySelectedAppsOutside() {
        val rules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = true,
            selectedPackages = setOf("com.telegram.messenger", "com.browser"),
            servicePackage = self,
            excludeServicePackage = true,
        )

        assertEquals(setOf<String>(), rules.allowedPackages)
        assertEquals(setOf("com.telegram.messenger", "com.browser", self), rules.disallowedPackages)
    }

    @Test
    fun awgBypassModeExcludesOnlySelectedAppsAndDoesNotInjectPingNg() {
        val rules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = true,
            selectedPackages = setOf("com.telegram.messenger"),
            servicePackage = self,
            excludeServicePackage = false,
        )

        assertEquals(setOf<String>(), rules.allowedPackages)
        assertEquals(setOf("com.telegram.messenger"), rules.disallowedPackages)
    }

    @Test
    fun psiphonOverAwgKeepsSelectedAppsOnTheSecondLayerAndPingNgOutside() {
        val proxyRules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = false,
            selectedPackages = setOf("com.telegram.messenger", self),
            servicePackage = self,
            excludeServicePackage = true,
        )
        val bypassRules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = true,
            selectedPackages = setOf("com.telegram.messenger"),
            servicePackage = self,
            excludeServicePackage = true,
        )

        assertEquals(setOf("com.telegram.messenger"), proxyRules.allowedPackages)
        assertEquals(setOf<String>(), proxyRules.disallowedPackages)
        assertEquals(setOf<String>(), bypassRules.allowedPackages)
        assertEquals(setOf("com.telegram.messenger", self), bypassRules.disallowedPackages)
    }

    @Test
    fun awgProxyModeAllowsSelectedAppsAndPingNgForChecks() {
        val proxyRules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = false,
            selectedPackages = setOf("com.telegram.messenger"),
            servicePackage = self,
            excludeServicePackage = false,
            includeServicePackageInProxyAllowlist = true,
        )
        val fullTunnelRules = PerAppVpnRoutingPolicy.create(
            enabled = false,
            bypassMode = false,
            selectedPackages = setOf("com.telegram.messenger"),
            servicePackage = self,
            excludeServicePackage = false,
        )

        assertEquals(setOf("com.telegram.messenger", self), proxyRules.allowedPackages)
        assertEquals(setOf<String>(), proxyRules.disallowedPackages)
        assertEquals(setOf<String>(), fullTunnelRules.allowedPackages)
        assertEquals(setOf<String>(), fullTunnelRules.disallowedPackages)
    }

    @Test
    fun awgBypassModeStillLetsTheUserBypassPingNgExplicitly() {
        val rules = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = true,
            selectedPackages = setOf("com.telegram.messenger", self),
            servicePackage = self,
            excludeServicePackage = false,
            includeServicePackageInProxyAllowlist = true,
        )

        assertEquals(setOf<String>(), rules.allowedPackages)
        assertEquals(setOf("com.telegram.messenger", self), rules.disallowedPackages)
    }

    @Test
    fun warpDisabledModeExcludesOnlyPingNgAndCannotCreateAnEmptyAllowlist() {
        val disabledRules = PerAppVpnRoutingPolicy.create(
            enabled = false,
            bypassMode = false,
            selectedPackages = setOf("com.telegram.messenger"),
            servicePackage = self,
            excludeServicePackage = true,
        )
        val selfOnlySelection = PerAppVpnRoutingPolicy.create(
            enabled = true,
            bypassMode = false,
            selectedPackages = setOf(self),
            servicePackage = self,
            excludeServicePackage = true,
        )

        assertEquals(setOf<String>(), disabledRules.allowedPackages)
        assertEquals(setOf(self), disabledRules.disallowedPackages)
        assertEquals(setOf<String>(), selfOnlySelection.allowedPackages)
        assertEquals(setOf(self), selfOnlySelection.disallowedPackages)
    }
}

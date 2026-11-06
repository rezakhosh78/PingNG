package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AwgWarpConfig
import com.v2ray.ang.core.WarpMasqueConfig
import com.v2ray.ang.core.WarpPlusConfig
import com.v2ray.ang.core.WarpWireGuardConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultProfileSeedFactoryTest {
    @Test
    fun seedsOnFreshInstallAndOnceForEachNewAppVersion() {
        assertTrue(shouldSeedDefaultProfiles(seededVersion = 0, currentVersion = 766))
        assertTrue(shouldSeedDefaultProfiles(seededVersion = 765, currentVersion = 766))
        assertFalse(shouldSeedDefaultProfiles(seededVersion = 766, currentVersion = 766))
        assertFalse(shouldSeedDefaultProfiles(seededVersion = 767, currentVersion = 766))
    }

    @Test
    fun createsTheFourVisibleDefaultProfilesAndKeepsPlusLayersManaged() {
        val batch = DefaultProfileSeedFactory.createMissing(
            existingDefaultProfiles = emptyList(),
            guidFactory = sequenceOf("masque", "wireguard", "awg", "plus", "outer", "inner").iterator()::next,
        )

        assertEquals(6, batch.profiles.size)
        assertEquals("masque", batch.preferredSelectionGuid)
        assertTrue(batch.profiles.getValue("masque").let {
            it.configType == EConfigType.WARP && WarpMasqueConfig.isDescription(it.description)
        })
        assertTrue(batch.profiles.getValue("wireguard").let {
            it.configType == EConfigType.WIREGUARD && WarpWireGuardConfig.isProfile(it)
        })
        assertTrue(batch.profiles.getValue("awg").let {
            it.configType == EConfigType.AMNEZIAWG && AwgWarpConfig.isProfile(it)
        })

        val plus = batch.profiles.getValue("plus")
        assertEquals(EConfigType.PROXYCHAIN, plus.configType)
        assertTrue(WarpPlusConfig.isDescription(plus.description))
        val children = batch.profiles.values.filter { it.managedBy == "plus" }
        assertEquals(2, children.size)
        assertTrue(children.all { it.subscriptionId == AppConfig.DEFAULT_SUBSCRIPTION_ID })
        assertTrue(children.all { it.secretKey.isNullOrBlank() })
        assertTrue(batch.profiles.values.all { it.subscriptionId == AppConfig.DEFAULT_SUBSCRIPTION_ID })
    }

    @Test
    fun createsOnlyMissingProfileKindsAndPrefersExistingMasqueForSelection() {
        val existingMasque = ProfileItem.create(EConfigType.WARP).apply {
            subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID
            description = WarpMasqueConfig.DESCRIPTION
        }
        val existingWireGuard = ProfileItem.create(EConfigType.WIREGUARD).apply {
            subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID
            description = WarpWireGuardConfig.DESCRIPTION
        }
        val existing = listOf("existing-masque" to existingMasque, "existing-wg" to existingWireGuard)

        val batch = DefaultProfileSeedFactory.createMissing(
            existingDefaultProfiles = existing,
            guidFactory = sequenceOf("awg", "plus", "outer", "inner").iterator()::next,
        )

        assertEquals(4, batch.profiles.size)
        assertFalse(batch.profiles.values.any { WarpMasqueConfig.isDescription(it.description) })
        assertFalse(batch.profiles.values.any(WarpWireGuardConfig::isProfile))
        assertTrue(batch.profiles.values.any(AwgWarpConfig::isProfile))
        assertTrue(batch.profiles.values.any { WarpPlusConfig.isDescription(it.description) })
        assertEquals("existing-masque", batch.preferredSelectionGuid)
    }

    @Test
    fun unrelatedProfilesDoNotCountAsDefaultWarpEntries() {
        val existingVmess = ProfileItem.create(EConfigType.VMESS).apply {
            subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID
        }
        val batch = DefaultProfileSeedFactory.createMissing(
            existingDefaultProfiles = listOf("vmess" to existingVmess),
            guidFactory = sequenceOf("masque", "wireguard", "awg", "plus", "outer", "inner").iterator()::next,
        )

        assertNotNull(batch.preferredSelectionGuid)
        assertEquals(6, batch.profiles.size)
        assertNull(batch.profiles.values.firstOrNull { it.configType == EConfigType.VMESS })
    }
}

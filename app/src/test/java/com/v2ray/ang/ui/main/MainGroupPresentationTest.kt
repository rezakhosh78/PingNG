package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainGroupPresentationTest {
    @Test
    fun startupLoadsOnlyTheSelectedGroupAndLeavesOtherGroupsLazy() {
        val groups = listOf("", "subscription-a", "subscription-b")

        assertEquals(listOf("subscription-b"), InitialGroupLoadPolicy.groupIds(groups, "subscription-b"))
        assertEquals(emptyList<String>(), InitialGroupLoadPolicy.groupIds(groups, "missing"))
    }

    @Test
    fun defaultGroupGetsNoticeAndRealDelayActionsButAllGroupDoesNot() {
        val isDefault = isDefaultSubscriptionGroup(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        assertTrue(isDefault)
        assertTrue(shouldShowGroupNotice(hasSubscriptionLink = false, isDefaultGroup = isDefault))
        assertTrue(canTestGroupRealDelay(hasSubscriptionLink = false, isDefaultGroup = isDefault))

        val isAllGroup = isDefaultSubscriptionGroup("")
        assertFalse(isAllGroup)
        assertFalse(shouldShowGroupNotice(hasSubscriptionLink = false, isDefaultGroup = isAllGroup))
        assertFalse(canTestGroupRealDelay(hasSubscriptionLink = false, isDefaultGroup = isAllGroup))
    }

    @Test
    fun linkedSubscriptionsKeepTheirNoticeAndRealDelayActions() {
        assertTrue(shouldShowGroupNotice(hasSubscriptionLink = true, isDefaultGroup = false))
        assertTrue(canTestGroupRealDelay(hasSubscriptionLink = true, isDefaultGroup = false))
    }

    @Test
    fun masterDnsEntriesAreOmittedFromGroupPingRequests() {
        val servers = listOf(
            ServersCache("regular", ProfileItem(configType = EConfigType.VMESS)),
            ServersCache("masterdns", ProfileItem(configType = EConfigType.SOCKS, description = "MasterDNS")),
            ServersCache("stormdns", ProfileItem(configType = EConfigType.SOCKS, description = "StormDNS")),
            ServersCache("dnstt", ProfileItem(configType = EConfigType.SOCKS, description = "DNSTT")),
        )

        assertEquals(listOf("regular"), pingableServerGuids(servers))
        assertEquals(0L, visiblePingDelayMillis(servers[1].profile, -1L))
    }

    @Test
    fun batchedPingResultsUpdateOnlyRowsWithResults() {
        val first = ServersCache("first", ProfileItem(configType = EConfigType.VMESS), 41L)
        val second = ServersCache("second", ProfileItem(configType = EConfigType.VLESS), 17L)
        val third = ServersCache("third", ProfileItem(configType = EConfigType.TROJAN), 8L)

        val updated = applyTestDelayResults(
            listOf(first, second, third),
            mapOf("first" to 95L, "third" to -1L, "missing" to 12L),
        )

        assertEquals(listOf(95L, 17L, -1L), updated.map { it.testDelayMillis })
        assertSame(second, updated[1])
        assertSame(first.profile, updated[0].profile)
        assertSame(third.profile, updated[2].profile)
    }

    @Test
    fun clearingBatchResultsLeavesUntouchedRowsAlone() {
        val first = ServersCache("first", ProfileItem(configType = EConfigType.VMESS), 41L)
        val second = ServersCache("second", ProfileItem(configType = EConfigType.VLESS), 17L)
        val servers = listOf(first, second)

        val updated = clearTestDelayResults(servers, setOf("first"))

        assertEquals(listOf(0L, 17L), updated.map { it.testDelayMillis })
        assertSame(second, updated[1])
        assertSame(servers, clearTestDelayResults(servers, emptySet()))
    }

    @Test
    fun defaultTrafficUsesAnUnlimitedQuotaAndStopButtonDispatchesStop() {
        val used = 2L * 1024L * 1024L * 1024L
        val defaultGroup = GroupMapItem(id = AppConfig.DEFAULT_SUBSCRIPTION_ID, remarks = "Default")
            .withDefaultTrafficUsage(used)

        assertEquals(0L, defaultGroup.trafficTotalBytes)
        assertEquals(used, defaultGroup.trafficUsedBytes)
        assertTrue(kotlin.math.abs(defaultTrafficUsageFraction(used) - 0.5f) < 0.0001f)
        assertTrue(defaultTrafficUsageFraction(-1L) == 0f)
        assertEquals(MainAction.StopService, serviceFabAction(isRunning = true))
        assertEquals(MainAction.ToggleService, serviceFabAction(isRunning = false))
    }
}

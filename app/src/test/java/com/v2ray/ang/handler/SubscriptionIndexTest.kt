package com.v2ray.ang.handler

import android.util.Log
import com.tencent.mmkv.MMKV
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SubscriptionIndexTest {
    private val mainValues = mutableMapOf<String, String>()
    private val subValues = mutableMapOf<String, String>()
    private val settingLongValues = mutableMapOf<String, Long>()
    private val settingIntValues = mutableMapOf<String, Int>()

    @BeforeEach
    fun prepareStorage() {
        for ((storage, values) in listOf(main to mainValues, subs to subValues)) {
            reset(storage)
            whenever(storage.decodeString(any())).thenAnswer { values[it.getArgument<String>(0)] }
            whenever(storage.encode(any<String>(), any<String>())).thenAnswer {
                values[it.getArgument(0)] = it.getArgument(1)
                true
            }
            whenever(storage.allKeys()).thenAnswer { values.keys.toTypedArray() }
        }
        settingLongValues.clear()
        settingIntValues.clear()
        reset(settings)
        whenever(settings.decodeLong(any<String>(), any<Long>())).thenAnswer {
            settingLongValues[it.getArgument<String>(0)] ?: it.getArgument<Long>(1)
        }
        whenever(settings.decodeInt(any<String>(), any<Int>())).thenAnswer {
            settingIntValues[it.getArgument<String>(0)] ?: it.getArgument<Int>(1)
        }
        whenever(settings.encode(any<String>(), any<Long>())).thenAnswer {
            settingLongValues[it.getArgument(0)] = it.getArgument(1)
            true
        }
        whenever(settings.encode(any<String>(), any<Int>())).thenAnswer {
            settingIntValues[it.getArgument(0)] = it.getArgument(1)
            true
        }
    }

    @Test
    fun duplicateIdsKeepTheirFirstPositionWithoutWritingStorage() {
        val stored = """["second","first","second","third","first"]"""
        mainValues["SUB_IDS"] = stored

        assertEquals(listOf("second", "first", "third"), MmkvManager.decodeSubsList())
        assertEquals(stored, mainValues["SUB_IDS"])
        verify(main, never()).encode(any<String>(), any<String>())
    }

    @Test
    fun duplicateIndexEntriesProduceOnlyOneSubscriptionRow() {
        mainValues["SUB_IDS"] = """["b","a","b","a"]"""
        subValues["a"] = JsonUtil.toJson(SubscriptionItem(remarks = "Alpha"))
        subValues["b"] = JsonUtil.toJson(SubscriptionItem(remarks = "Beta"))

        val rows = MmkvManager.decodeSubscriptions()

        assertEquals(listOf("b", "a"), rows.map { it.guid })
        assertEquals(listOf("Beta", "Alpha"), rows.map { it.subscription.remarks })
    }

    @Test
    fun repeatedAndBlankNamesDoNotMergeDifferentSubscriptions() {
        mainValues["SUB_IDS"] = """["a","b","c","d"]"""
        listOf("a" to "Same", "b" to "Same", "c" to "", "d" to " ").forEach { (id, name) ->
            subValues[id] = JsonUtil.toJson(SubscriptionItem(remarks = name))
        }

        assertEquals(listOf("a", "b", "c", "d"), MmkvManager.decodeSubscriptions().map { it.guid })
    }

    @Test
    fun decodedIndexRemainsMutableAndCanBeSavedInANewOrder() {
        mainValues["SUB_IDS"] = """["a","b","a"]"""
        val ids = MmkvManager.decodeSubsList()
        ids.remove("a")
        ids.add(0, "c")
        MmkvManager.encodeSubsList(ids)

        assertEquals("""["c","b"]""", mainValues["SUB_IDS"])
        assertEquals(listOf("c", "b"), MmkvManager.decodeSubsList())
    }

    @Test
    fun missingBlankAndEmptyIndexesRemainEmpty() {
        assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        listOf("", " ", "[]", "null").forEach { stored ->
            mainValues["SUB_IDS"] = stored
            assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        }
    }

    @Test
    fun malformedIndexKeepsTheExistingEmptyFallback() {
        mockStatic(Log::class.java).use {
            mainValues["SUB_IDS"] = "{"
            assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        }
    }

    @Test
    fun localTrafficGraphPersistsQuotaAndResetsOrRollsOverPerSubscription() {
        val subscriptionId = "worker-a"
        MmkvManager.setLocalSubscriptionTrafficGraph(subscriptionId, limitBytes = 8_000L, periodDays = 1)

        assertEquals(8_000L, MmkvManager.getLocalSubscriptionTrafficLimitBytes(subscriptionId))
        assertEquals(1, MmkvManager.getLocalSubscriptionTrafficPeriodDays(subscriptionId))
        assertEquals(0L, MmkvManager.getLocalSubscriptionTrafficUsedBytes(subscriptionId))
        assertEquals(0L, MmkvManager.getLocalSubscriptionTrafficLimitBytes("worker-b"))

        MmkvManager.addLocalSubscriptionTrafficUsedBytes(subscriptionId, 120L)
        assertEquals(120L, MmkvManager.getLocalSubscriptionTrafficUsedBytes(subscriptionId))

        settingLongValues["pref_local_subscription_traffic_${subscriptionId}_window_start_ms"] =
            System.currentTimeMillis() - 24L * 60L * 60L * 1000L - 1L
        MmkvManager.addLocalSubscriptionTrafficUsedBytes(subscriptionId, 8L)
        assertEquals(8L, MmkvManager.getLocalSubscriptionTrafficUsedBytes(subscriptionId))

        MmkvManager.resetLocalSubscriptionTrafficUsage(subscriptionId)
        assertEquals(0L, MmkvManager.getLocalSubscriptionTrafficUsedBytes(subscriptionId))
        assertEquals(8_000L, MmkvManager.getLocalSubscriptionTrafficLimitBytes(subscriptionId))
    }

    companion object {
        private val main: MMKV = mock()
        private val subs: MMKV = mock()
        private val settings: MMKV = mock()

        @BeforeAll
        @JvmStatic
        fun initializeHandles() {
            mockStatic(MMKV::class.java).use {
                it.`when`<MMKV> { MMKV.mmkvWithID("MAIN", MMKV.MULTI_PROCESS_MODE) }.thenReturn(main)
                it.`when`<MMKV> { MMKV.mmkvWithID("SUB", MMKV.MULTI_PROCESS_MODE) }.thenReturn(subs)
                it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                MmkvManager.decodeSubscriptions()
                MmkvManager.decodeSettingsString("test-initialize")
            }
        }
    }
}

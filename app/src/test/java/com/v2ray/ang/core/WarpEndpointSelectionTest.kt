package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpEndpointSelectionTest {
    @Test
    fun fastVerifyCountFollowsHitsInsteadOfMultiplyingThem() {
        val inner = (1..8).map { WarpEndpointTester.Endpoint("188.114.96.$it", 2408) }
        val outer = (20..25).map { WarpEndpointTester.Endpoint("162.159.195.$it", 2408) }
        val pairs = WarpEndpointTester.buildCandidatePairs(
            WarpPlusConfig.ENDPOINT_MODE_FAST, inner, outer, inner, outer,
        )

        assertEquals(8, pairs.size)
        assertEquals(inner.toSet(), pairs.map { it.first }.toSet())
        assertEquals(outer.toSet(), pairs.map { it.second }.toSet())
    }

    @Test
    fun mediumVerifyRequiresAHandshakeFromBothWARPIdentities() {
        val outerOnly = WarpEndpointTester.Endpoint("188.114.96.1", 2408)
        val both = WarpEndpointTester.Endpoint("162.159.195.1", 2408)
        val pairs = WarpEndpointTester.buildCandidatePairs(
            WarpPlusConfig.ENDPOINT_MODE_MEDIUM,
            listOf(outerOnly, both),
            listOf(outerOnly, both),
            listOf(both),
            listOf(outerOnly, both),
        )

        assertEquals(listOf(both to both), pairs)
    }

    @Test
    fun fastVerifyOnlyUsesEndpointsThatAnsweredForTheirOwnHop() {
        val old = WarpEndpointTester.Endpoint("188.114.96.206", 878)
        val inner = WarpEndpointTester.Endpoint("188.114.96.1", 2408)
        val outer = WarpEndpointTester.Endpoint("162.159.195.1", 2408)
        val pairs = WarpEndpointTester.buildCandidatePairs(
            WarpPlusConfig.ENDPOINT_MODE_FAST,
            listOf(old, inner),
            listOf(old, outer),
            listOf(inner),
            listOf(outer),
        )

        assertEquals(listOf(inner to outer), pairs)
        assertTrue(pairs.none { it.first == old || it.second == old })
    }
}

package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BepassNetworkSelectionPolicyTest {
    @Test
    fun choosesValidatedInternetNetworkAndSkipsVpnInterfaces() {
        val selected = BepassNetworkSelectionPolicy.best(
            listOf(
                BepassNetworkCandidate("vpn", true, false, true, 3),
                BepassNetworkCandidate("wifi", true, true, true, 3),
                BepassNetworkCandidate("cellular", true, true, false, 2),
                BepassNetworkCandidate("local", false, true, true, 3),
            ),
        )

        assertEquals("wifi", selected)
    }

    @Test
    fun selectsAnAvailableReplacementWhenTheBoundNetworkIsLost() {
        val selected = BepassNetworkSelectionPolicy.best(
            listOf(
                BepassNetworkCandidate("lost-wifi", true, true, true, 3),
                BepassNetworkCandidate("cellular", true, true, true, 2),
            ),
            excludedNetwork = "lost-wifi",
        )

        assertEquals("cellular", selected)
    }

    @Test
    fun returnsNoRouteWhenOnlyVpnOrOfflineNetworksExist() {
        val selected = BepassNetworkSelectionPolicy.best(
            listOf(
                BepassNetworkCandidate("vpn", true, false, true, 3),
                BepassNetworkCandidate("offline", false, true, false, 2),
            ),
        )

        assertNull(selected)
    }
}

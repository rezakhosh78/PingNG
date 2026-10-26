package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainTestRequestPolicyTest {
    @Test
    fun acceptsOnlyTheCurrentlyActiveRequestId() {
        assertTrue(isCurrentPingRequest("active", "active"))
        assertFalse(isCurrentPingRequest("active", "previous"))
        assertFalse(isCurrentPingRequest("active", ""))
        assertFalse(isCurrentPingRequest(null, "active"))
    }
}

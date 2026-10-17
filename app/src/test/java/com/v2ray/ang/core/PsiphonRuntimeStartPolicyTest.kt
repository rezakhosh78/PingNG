package com.v2ray.ang.core

import com.v2ray.ang.dto.PsiphonStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PsiphonRuntimeStartPolicyTest {
    @Test
    fun reusesOnlyTheSameConnectedOrRecentlyStartingRuntime() {
        assertTrue(PsiphonRuntimeStartPolicy.shouldReuseRuntime("same", "same", true, true, 1_000, 90_000))
        assertTrue(PsiphonRuntimeStartPolicy.shouldReuseRuntime("same", "same", true, false, 10_000, 20_000))
        assertFalse(PsiphonRuntimeStartPolicy.shouldReuseRuntime("old", "new", true, true, 1_000, 2_000))
        assertFalse(PsiphonRuntimeStartPolicy.shouldReuseRuntime("same", "same", true, false, 1_000, 90_000))
    }

    @Test
    fun egressProbeOnlyPublishesConnectedAfterSuccess() {
        assertEquals(
            PsiphonStatus.CONNECTED,
            PsiphonRuntimeStartPolicy.statusAfterEgressProbe(transportConnected = true, probeSucceeded = true),
        )
        assertNull(PsiphonRuntimeStartPolicy.statusAfterEgressProbe(transportConnected = true, probeSucceeded = false))
        assertNull(PsiphonRuntimeStartPolicy.statusAfterEgressProbe(transportConnected = false, probeSucceeded = true))
    }

    @Test
    fun egressFailureStopsOnlyAnActiveTunnelAndIsHandledOnce() {
        assertTrue(PsiphonRuntimeStartPolicy.shouldStopAfterEgressFailure(transportConnected = true, failureAlreadyHandled = false))
        assertFalse(PsiphonRuntimeStartPolicy.shouldStopAfterEgressFailure(transportConnected = true, failureAlreadyHandled = true))
        assertFalse(PsiphonRuntimeStartPolicy.shouldStopAfterEgressFailure(transportConnected = false, failureAlreadyHandled = false))
    }

    @Test
    fun socksOrPreviouslyActiveRuntimeFailuresDisconnectWithoutSurfacingAnError() {
        assertTrue(PsiphonRuntimeStartPolicy.shouldQuietlyDisconnectAfterRuntimeFailure(false, "SOCKS port unavailable"))
        assertTrue(PsiphonRuntimeStartPolicy.shouldQuietlyDisconnectAfterRuntimeFailure(true, "native runtime failed"))
        assertFalse(PsiphonRuntimeStartPolicy.shouldQuietlyDisconnectAfterRuntimeFailure(false, "invalid startup parameters"))
    }

    @Test
    fun automaticPsiphonEgressCheckRunsWithoutTheFormerStartupWait() {
        assertEquals(120L, PsiphonRuntimeStartPolicy.automaticEgressProbeDelayMs(false, true, true))
        assertEquals(200L, PsiphonRuntimeStartPolicy.automaticEgressProbeDelayMs(true, true, true))
        assertEquals(1_200L, PsiphonRuntimeStartPolicy.automaticEgressProbeDelayMs(false, false, true))
        assertEquals(900L, PsiphonRuntimeStartPolicy.automaticEgressProbeDelayMs(false, false, false))
    }

    @Test
    fun timedOutRuntimeGetsAtMostTwoAutomaticRetries() {
        assertTrue(PsiphonRuntimeStartPolicy.shouldRetryTimedOutStart(0))
        assertTrue(PsiphonRuntimeStartPolicy.shouldRetryTimedOutStart(1))
        assertFalse(PsiphonRuntimeStartPolicy.shouldRetryTimedOutStart(2))
    }
}

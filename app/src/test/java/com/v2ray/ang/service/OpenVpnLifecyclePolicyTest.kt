package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenVpnLifecyclePolicyTest {
    @Test
    fun `normal and repeated disconnect only start one native stop worker`() {
        assertTrue(OpenVpnLifecyclePolicy.shouldStartStopWorker(stopAlreadyStarted = false))
        assertFalse(OpenVpnLifecyclePolicy.shouldStartStopWorker(stopAlreadyStarted = true))
    }

    @Test
    fun `connection checks run only while connected and not stopping`() {
        assertTrue(OpenVpnLifecyclePolicy.mayProbe(connected = true, stopRequested = false))
        assertFalse(OpenVpnLifecyclePolicy.mayProbe(connected = false, stopRequested = false))
        assertFalse(OpenVpnLifecyclePolicy.mayProbe(connected = true, stopRequested = true))
    }

    @Test
    fun `native startup exits after service cancellation or user stop`() {
        assertTrue(OpenVpnLifecyclePolicy.mayContinueStartup(startupCancelled = false, stopRequested = false))
        assertFalse(OpenVpnLifecyclePolicy.mayContinueStartup(startupCancelled = true, stopRequested = false))
        assertFalse(OpenVpnLifecyclePolicy.mayContinueStartup(startupCancelled = false, stopRequested = true))
    }

    @Test
    fun `native start waits for an in-flight probe and then reserves the client`() {
        assertTrue(
            OpenVpnLifecyclePolicy.shouldWaitForNativeClientReservation(
                hasActiveClient = false,
                stopRequested = false,
                hasProbeClient = true,
                remainingNanos = 1,
                startGeneration = 4,
                currentGeneration = 4,
            ),
        )
        assertTrue(
            OpenVpnLifecyclePolicy.mayAcquireNativeClient(
                hasActiveClient = false,
                hasProbeClient = false,
                startGeneration = 4,
                currentGeneration = 4,
            ),
        )
    }

    @Test
    fun `stop during a queued native start prevents that start from acquiring the client`() {
        assertFalse(
            OpenVpnLifecyclePolicy.shouldWaitForNativeClientReservation(
                hasActiveClient = false,
                stopRequested = false,
                hasProbeClient = true,
                remainingNanos = 1,
                startGeneration = 4,
                currentGeneration = 5,
            ),
        )
        assertFalse(
            OpenVpnLifecyclePolicy.mayAcquireNativeClient(
                hasActiveClient = false,
                hasProbeClient = false,
                startGeneration = 4,
                currentGeneration = 5,
            ),
        )
        assertTrue(OpenVpnLifecyclePolicy.wasNativeStartCanceled(4, 5))
    }

    @Test
    fun `a duplicate native start does not wait behind a live client`() {
        assertFalse(
            OpenVpnLifecyclePolicy.shouldWaitForNativeClientReservation(
                hasActiveClient = true,
                stopRequested = false,
                hasProbeClient = false,
                remainingNanos = 1,
                startGeneration = 4,
                currentGeneration = 4,
            ),
        )
        assertFalse(
            OpenVpnLifecyclePolicy.mayAcquireNativeClient(
                hasActiveClient = true,
                hasProbeClient = false,
                startGeneration = 4,
                currentGeneration = 4,
            ),
        )
    }
}

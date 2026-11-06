package com.v2ray.ang.service

/** Pure lifecycle guards shared by OpenVPN service code and JVM regression tests. */
internal object OpenVpnLifecyclePolicy {
    fun shouldStartStopWorker(stopAlreadyStarted: Boolean): Boolean = !stopAlreadyStarted

    fun mayProbe(connected: Boolean, stopRequested: Boolean): Boolean =
        connected && !stopRequested

    fun mayContinueStartup(startupCancelled: Boolean, stopRequested: Boolean): Boolean =
        !startupCancelled && !stopRequested

    fun shouldWaitForNativeClientReservation(
        hasActiveClient: Boolean,
        stopRequested: Boolean,
        hasProbeClient: Boolean,
        remainingNanos: Long,
        startGeneration: Long,
        currentGeneration: Long,
    ): Boolean =
        ((hasActiveClient && stopRequested) || (!hasActiveClient && hasProbeClient)) &&
            remainingNanos > 0 && startGeneration == currentGeneration

    fun mayAcquireNativeClient(
        hasActiveClient: Boolean,
        hasProbeClient: Boolean,
        startGeneration: Long,
        currentGeneration: Long,
    ): Boolean =
        !hasActiveClient && !hasProbeClient && startGeneration == currentGeneration

    fun wasNativeStartCanceled(startGeneration: Long, currentGeneration: Long): Boolean =
        startGeneration != currentGeneration
}

package com.v2ray.ang.core

import com.v2ray.ang.dto.PsiphonStatus

/** Small lifecycle decisions for the isolated Psiphon runtime. */
internal object PsiphonRuntimeStartPolicy {
    const val START_TIMEOUT_MS = 60_000L
    const val MAX_START_RETRIES = 2

    fun shouldReuseRuntime(
        activeGuid: String?,
        requestedGuid: String,
        isRunning: Boolean,
        isConnected: Boolean,
        startedAtMs: Long,
        nowMs: Long,
    ): Boolean = activeGuid == requestedGuid && isRunning &&
        (isConnected || (startedAtMs > 0L && nowMs - startedAtMs < START_TIMEOUT_MS))

    fun statusAfterEgressProbe(transportConnected: Boolean, probeSucceeded: Boolean): String? =
        if (transportConnected && probeSucceeded) PsiphonStatus.CONNECTED else null

    fun shouldStopAfterEgressFailure(transportConnected: Boolean, failureAlreadyHandled: Boolean): Boolean =
        transportConnected && !failureAlreadyHandled

    fun shouldQuietlyDisconnectAfterRuntimeFailure(
        psiphonWasActive: Boolean,
        failureMessage: String,
    ): Boolean = psiphonWasActive || failureMessage.contains("socks", ignoreCase = true)

    fun automaticEgressProbeDelayMs(
        quickSearchProbe: Boolean,
        psiphonProfile: Boolean,
        secondLayerProfile: Boolean,
    ): Long = when {
        quickSearchProbe -> 200L
        psiphonProfile -> 120L
        secondLayerProfile -> 1_200L
        else -> 900L
    }

    fun shouldRetryTimedOutStart(retryCount: Int): Boolean = retryCount < MAX_START_RETRIES
}

package com.v2ray.ang.core

internal enum class BepassRuntimeStartOutcome {
    READY,
    REPORTED_FAILURE,
    WAIT_TIMEOUT,
}

internal object BepassRuntimeRecoveryPolicy {
    fun shouldResetIsolatedProcess(outcome: BepassRuntimeStartOutcome): Boolean =
        outcome == BepassRuntimeStartOutcome.WAIT_TIMEOUT
}

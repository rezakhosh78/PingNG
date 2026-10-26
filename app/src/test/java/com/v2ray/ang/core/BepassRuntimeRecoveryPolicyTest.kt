package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BepassRuntimeRecoveryPolicyTest {
    @Test
    fun onlyAnUnansweredStartupTimeoutResetsTheIsolatedProcess() {
        assertTrue(
            BepassRuntimeRecoveryPolicy.shouldResetIsolatedProcess(
                BepassRuntimeStartOutcome.WAIT_TIMEOUT,
            ),
        )
        assertFalse(
            BepassRuntimeRecoveryPolicy.shouldResetIsolatedProcess(
                BepassRuntimeStartOutcome.REPORTED_FAILURE,
            ),
        )
        assertFalse(
            BepassRuntimeRecoveryPolicy.shouldResetIsolatedProcess(
                BepassRuntimeStartOutcome.READY,
            ),
        )
    }
}

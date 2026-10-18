package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainTestRequestsTest {
    @Test
    fun masqueRegistrationOnlyReadsStringGuidsFromKnownMessages() {
        assertEquals(
            "registration-guid",
            MasqueRegistrationMessagePolicy.registrationGuid(
                com.v2ray.ang.AppConfig.MSG_MASQUE_REGISTRATION_READY,
                "registration-guid",
            ),
        )
        assertNull(
            MasqueRegistrationMessagePolicy.registrationGuid(
                com.v2ray.ang.AppConfig.MSG_MASQUE_REGISTRATION_READY,
                42,
            ),
        )
        assertNull(MasqueRegistrationMessagePolicy.registrationGuid(0, "unrelated"))
    }

    @Test
    fun failedDelayWithAnExitIpIsStillRecognizedAsReachable() {
        assertTrue(
            ConnectionTestResult(delayMillis = -1L, ipAddress = "203.0.113.10")
                .isTunnelReachableWithoutDelay(),
        )
        assertFalse(ConnectionTestResult(delayMillis = -1L).isTunnelReachableWithoutDelay())
        assertFalse(
            ConnectionTestResult(delayMillis = 120L, ipAddress = "203.0.113.10")
                .isTunnelReachableWithoutDelay(),
        )
    }

    @Test
    fun onlyTheLatestCurrentRequestCanFinishOnce() {
        val requests = MainTestRequests()
        assertFalse(requests.isTesting)
        assertFalse(requests.completeCurrent(""))
        val first = requests.beginCurrent()
        val second = requests.beginCurrent()
        assertNotEquals(first, second)
        assertFalse(requests.completeCurrent(first))
        assertTrue(requests.isTesting)
        assertTrue(requests.completeCurrent(second))
        assertFalse(requests.completeCurrent(second))
        assertFalse(requests.isTesting)
    }

    @Test
    fun stopAndRestartInvalidateThePreviousConnectionRequest() {
        val requests = MainTestRequests()
        val stopped = requests.beginCurrent()
        requests.invalidateCurrent()
        assertFalse(requests.completeCurrent(stopped))
        val restarted = requests.beginCurrent()
        assertFalse(requests.completeCurrent(stopped))
        assertTrue(requests.completeCurrent(restarted))
    }

    @Test
    fun bulkCancellationAndReplacementRejectLateCompletionEvenInTheSameGroup() {
        val requests = MainTestRequests()
        val cancelled = requests.beginBulk("group")
        requests.cancelBulk()
        assertNull(requests.completeBulk(cancelled.id))
        val replaced = requests.beginBulk("group")
        val current = requests.beginBulk("group")
        assertNull(requests.completeBulk(replaced.id))
        assertEquals(current, requests.bulk)
        assertEquals(current, requests.completeBulk(current.id))
        assertNull(requests.completeBulk(current.id))
        assertFalse(requests.isTesting)
    }

    @Test
    fun currentResultDoesNotFinishAnIndependentBulkTest() {
        val requests = MainTestRequests()
        val bulk = requests.beginBulk("") // All servers is a valid group ID.
        val current = requests.beginCurrent()
        assertTrue(requests.completeCurrent(current))
        assertTrue(requests.isTesting)
        assertEquals(bulk, requests.completeBulk(bulk.id))
        assertFalse(requests.isTesting)
    }
}

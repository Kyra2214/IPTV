package com.kyra.iptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test fun defaultsToThreeAttemptsWithGrowingDelay() {
        val p = RetryPolicy()
        assertEquals(3, p.maxAttempts)
        assertEquals(1_000L, p.delayFor(0))
        assertEquals(2_000L, p.delayFor(1))
        assertEquals(4_000L, p.delayFor(2))
        assertNull(p.delayFor(3))
    }

    @Test fun customDelays() {
        val p = RetryPolicy(listOf(500L))
        assertEquals(1, p.maxAttempts)
        assertEquals(500L, p.delayFor(0))
        assertNull(p.delayFor(1))
    }

    @Test fun onlyTransientHttpStatusesAreRetriable() {
        assertTrue(RetryPolicy.isRetriableStatus(408))
        assertTrue(RetryPolicy.isRetriableStatus(429))
        assertTrue(RetryPolicy.isRetriableStatus(500))
        assertTrue(RetryPolicy.isRetriableStatus(503))
        assertFalse(RetryPolicy.isRetriableStatus(401))
        assertFalse(RetryPolicy.isRetriableStatus(403))
        assertFalse(RetryPolicy.isRetriableStatus(404))
    }
}

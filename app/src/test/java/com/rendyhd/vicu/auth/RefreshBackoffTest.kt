package com.rendyhd.vicu.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshBackoffTest {

    @Test
    fun `first server error schedules 5s backoff`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 0)
        assertEquals(5_000L, delay)
    }

    @Test
    fun `network error follows the same exponential curve as server error`() {
        assertEquals(5_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.NetworkError, 0))
        assertEquals(10_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.NetworkError, 1))
        assertEquals(20_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.NetworkError, 2))
    }

    @Test
    fun `consecutive failures double up to 120s cap`() {
        assertEquals(5_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 0))
        assertEquals(10_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 1))
        assertEquals(20_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 2))
        assertEquals(40_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 3))
        assertEquals(80_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 4))
        // From here on, the cap holds.
        assertEquals(120_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 5))
        assertEquals(120_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 6))
        assertEquals(120_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 99))
    }

    @Test
    fun `rate limited with Retry-After 90 honors the header`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.RateLimited(retryAfterSecs = 90), 0)
        assertEquals(90_000L, delay)
    }

    @Test
    fun `rate limited with Retry-After 30 is floored to 60s`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.RateLimited(retryAfterSecs = 30), 0)
        assertEquals(60_000L, delay)
    }

    @Test
    fun `rate limited ignores prior consecutive failure count`() {
        // RateLimited should always honor the header; it doesn't double like 5xx.
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.RateLimited(retryAfterSecs = 90), 4)
        assertEquals(90_000L, delay)
    }

    @Test
    fun `Unauthorized is treated as terminal`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.Unauthorized, 0)
        assertTrue("Expected a very large delay, got $delay", delay >= 1_000_000_000L)
    }

    @Test
    fun `NoRefreshToken is treated as terminal`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.NoRefreshToken, 0)
        assertTrue("Expected a very large delay, got $delay", delay >= 1_000_000_000L)
    }

    @Test
    fun `EmptyTokenReturned uses transient backoff curve`() {
        assertEquals(5_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.EmptyTokenReturned, 0))
        assertEquals(10_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.EmptyTokenReturned, 1))
    }
}

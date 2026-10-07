package com.rendyhd.vicu.auth

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthManagerRefreshTest {

    @Test
    fun `a transport failure during refresh is a NetworkError`() = runTest {
        val h = authHarness(backgroundScope) {
            throw HttpRequestTimeoutException("https://vikunja.test/api/v2/user/token/refresh", 1_000L)
        }
        h.storage.storeRefreshToken("r1")

        val result = h.manager.performV2RefreshTyped()

        assertEquals(RefreshResult.Failure(RefreshFailure.NetworkError), result)
    }

    @Test
    fun `an unexpected failure during refresh stays a ServerError`() = runTest {
        val h = authHarness(backgroundScope) { throw IllegalStateException("boom") }
        h.storage.storeRefreshToken("r1")

        val result = h.manager.performV2RefreshTyped()

        assertEquals(RefreshResult.Failure(RefreshFailure.ServerError), result)
    }

    @Test
    fun `a transport failure backs off like any transient failure`() = runTest {
        val h = authHarness(backgroundScope) {
            throw HttpRequestTimeoutException("https://vikunja.test/api/v2/user/token/refresh", 1_000L)
        }
        h.storage.storeRefreshToken("r1")

        h.manager.performV2RefreshTyped()

        assertFalse(h.manager.canAttemptRefreshNow())
    }

    @Test
    fun `cancelling a refresh does not record a failure`() = runTest {
        val h = authHarness(backgroundScope) { throw CancellationException("cancelled") }
        h.storage.storeRefreshToken("r1")

        assertFailsWith<CancellationException> { h.manager.performV2RefreshTyped() }

        assertTrue(h.manager.canAttemptRefreshNow(), "A cancelled refresh must not start a backoff window")
    }

    @Test
    fun `http failures keep their dedicated classification`() = runTest {
        val h = authHarness(backgroundScope) { respondJson("{}", HttpStatusCode.InternalServerError) }
        h.storage.storeRefreshToken("r1")

        val result = h.manager.performV2RefreshTyped()

        assertEquals(RefreshResult.Failure(RefreshFailure.ServerError), result)
    }

    @Test
    fun `a successful refresh clears the backoff before the next refresh is scheduled`() = runTest {
        val calls = Counter()
        val h = authHarness(backgroundScope) {
            if (calls.increment() == 1) {
                // Unauthorized is terminal: it parks the backoff floor far in the future.
                respondJson("{}", HttpStatusCode.Unauthorized)
            } else {
                respondJson("""{"token":"${jwtExpiringIn(600)}"}""")
            }
        }
        h.storage.storeRefreshToken("r1")

        assertIs<RefreshResult.Failure>(h.manager.performV2RefreshTyped())
        assertFalse(h.manager.canAttemptRefreshNow())

        assertEquals(RefreshResult.Success, h.manager.performV2RefreshTyped())
        assertTrue(h.manager.canAttemptRefreshNow())
        assertEquals(2, calls.value)

        // The JWT lives 600 s and the manager refreshes 120 s early. If the stale terminal
        // floor were still in place when the timer was scheduled, nothing would ever fire.
        testScheduler.advanceTimeBy(500_000L)
        testScheduler.runCurrent()
        awaitCondition { calls.value >= 3 }
        assertEquals(3, calls.value, "Proactive refresh should have fired from the fresh schedule")
    }
}

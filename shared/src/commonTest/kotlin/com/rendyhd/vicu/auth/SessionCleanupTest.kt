package com.rendyhd.vicu.auth

import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_TOKEN
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_USER_ID
import com.rendyhd.vicu.auth.LoginHarness.Companion.userJson
import com.rendyhd.vicu.data.local.WiperFixture
import com.rendyhd.vicu.data.local.queuedAction
import com.rendyhd.vicu.worker.awaitUntil
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCleanupTest {

    private fun fixtureWithWork() = WiperFixture().apply {
        dao.taskIds += listOf(1, 2, -3)
        dao.pending += queuedAction(entityId = 1)
        dao.pending += queuedAction(entityId = -3, actionType = "create", status = "failed")
        dao.addRoutineHistory("routine-a:2026-01-01")
    }

    /** What the database held at the moment the server logout request arrived. */
    private class LogoutProbe {
        var pendingAtLogout: Int? = null
        var tasksAtLogout: Int? = null
    }

    private fun harness(
        fixture: WiperFixture,
        probe: LogoutProbe = LogoutProbe(),
        logoutDelayMs: Long = 0,
    ) = LoginHarness(fixture = fixture) { request ->
        when {
            request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/logout") -> {
                probe.pendingAtLogout = fixture.dao.pending.size
                probe.tasksAtLogout = fixture.dao.taskIds.size
                if (logoutDelayMs > 0) delay(logoutDelayMs)
                respond("{}", HttpStatusCode.OK, authTestJsonHeaders)
            }
            request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/user") -> userJson(ORIGINAL_USER_ID)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    // --- signOut ---

    @Test
    fun `signing out with queued changes needs an explicit discard and touches nothing`() = runTest {
        val f = fixtureWithWork()
        val h = harness(f)
        h.signIn()

        val result = h.sessionCleanup.signOut(discardUnsynced = false)

        assertEquals(SignOutResult.NeedsDiscard(2), result)
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(ORIGINAL_TOKEN, h.storage.getApiToken())
        assertEquals(2, f.dao.pending.size)
        assertEquals(setOf(1L, 2L, -3L), f.dao.taskIds)
        assertTrue(h.requests().none { it.url.endsWith("/logout") })
        h.close()
    }

    @Test
    fun `signing out with discard cleans the server session and tokens before wiping everything`() = runTest {
        val f = fixtureWithWork()
        val probe = LogoutProbe()
        val h = harness(f, probe)
        h.signIn()

        val result = h.sessionCleanup.signOut(discardUnsynced = true)

        assertEquals(SignOutResult.Done, result)
        // The logout request went out while the data was still there: tokens go first.
        assertEquals(2, probe.pendingAtLogout)
        assertEquals(3, probe.tasksAtLogout)
        assertEquals(AuthState.Unauthenticated, h.authManager.authState.value)
        assertNull(h.storage.getApiToken())
        assertNull(h.storage.getUserId())
        assertTrue(f.dao.taskIds.isEmpty() && f.dao.pending.isEmpty() && f.dao.routineArchive.isEmpty())
        assertEquals(1, f.customLists.clearLocalCalls)
        assertEquals(1, f.hooks.cancelAllAlarmsCalls, "reminder, snooze and routine alarms are cancelled")
        h.close()
    }

    @Test
    fun `signing out with nothing queued needs no confirmation`() = runTest {
        val f = WiperFixture().apply { dao.taskIds += 1 }
        val h = harness(f)
        h.signIn()

        assertEquals(SignOutResult.Done, h.sessionCleanup.signOut(discardUnsynced = false))
        assertTrue(f.dao.taskIds.isEmpty())
        h.close()
    }

    @Test
    fun `a sign-out whose caller is cancelled still finishes both halves`() = runTest {
        val f = fixtureWithWork()
        val probe = LogoutProbe()
        val h = harness(f, probe, logoutDelayMs = 300)
        h.signIn()

        withContext(Dispatchers.Default) {
            val job = async { h.sessionCleanup.signOut(discardUnsynced = true) }
            awaitUntil { probe.pendingAtLogout != null }
            // Leaving Settings cancels the ViewModel scope while the logout request is in flight.
            job.cancelAndJoin()
            awaitUntil { f.dao.pending.isEmpty() && h.authManager.authState.value == AuthState.Unauthenticated }
        }

        assertNull(h.storage.getApiToken(), "tokens are gone")
        assertTrue(f.dao.taskIds.isEmpty() && f.dao.pending.isEmpty(), "data is gone")
        h.close()
    }

    // --- clearCaches ---

    @Test
    fun `clearing caches keeps queued changes unless they are discarded`() = runTest {
        val f = fixtureWithWork()
        val h = harness(f)

        h.sessionCleanup.clearCaches(discardUnsynced = false)

        assertEquals(2, f.dao.pending.size)
        assertEquals(setOf(1L, -3L), f.dao.taskIds, "rows the queue refers to stay")
        assertEquals(1, f.dao.routineArchive.size)

        h.sessionCleanup.clearCaches(discardUnsynced = true)

        assertTrue(f.dao.pending.isEmpty() && f.dao.taskIds.isEmpty())
        assertEquals(1, f.dao.routineArchive.size, "routine history is never part of a cache clear")
        h.close()
    }
}

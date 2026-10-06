package com.rendyhd.vicu.auth

import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_SERVER
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_TOKEN
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_USER_ID
import com.rendyhd.vicu.auth.LoginHarness.Companion.OTHER_SERVER
import com.rendyhd.vicu.auth.LoginHarness.Companion.unauthorized
import com.rendyhd.vicu.auth.LoginHarness.Companion.userJson
import com.rendyhd.vicu.data.local.WiperFixture
import com.rendyhd.vicu.data.local.queuedAction
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountSessionTest {

    /** Knows two tokens: "good" belongs to user 8, anything else is rejected. */
    private fun harness(fixture: WiperFixture = WiperFixture(), signedIn: Boolean = true) =
        LoginHarness(signedIn = signedIn, fixture = fixture) { request ->
            val bearer = request.headers[HttpHeaders.Authorization]
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/user") ->
                    when (bearer) {
                        "Bearer good" -> userJson(8)
                        "Bearer $ORIGINAL_TOKEN" -> userJson(ORIGINAL_USER_ID)
                        else -> unauthorized()
                    }
                else -> error("Unexpected request ${request.method.value} ${request.url}")
            }
        }

    // --- verify ---

    @Test
    fun `verify asks the given server with the given token and stores nothing`() = runTest {
        val h = harness()
        h.signIn()

        val identity = h.accountSession.verify(OTHER_SERVER, "good")

        assertEquals(AccountIdentity(OTHER_SERVER, 8), identity)
        val request = h.requests().last()
        assertEquals("GET $OTHER_SERVER/api/v2/user", request.toString())
        assertEquals(listOf("Bearer good"), request.authorization, "exactly the new token, never the stored one")
        assertEquals(ORIGINAL_TOKEN, h.storage.getApiToken())
        assertEquals(ORIGINAL_SERVER, h.storage.getVikunjaUrl())
        assertEquals(ORIGINAL_USER_ID, h.storage.getUserId())
        h.close()
    }

    @Test
    fun `a rejected token fails without touching the session`() = runTest {
        val h = harness()
        h.signIn()

        assertFailsWith<VikunjaApiException> { h.accountSession.verify(OTHER_SERVER, "bad") }

        // A 401 on the normal client would trigger a refresh and then sign the session out.
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(ORIGINAL_TOKEN, h.authManager.getBestTokenSync())
        assertEquals(ORIGINAL_TOKEN, h.storage.getApiToken())
        assertTrue(h.requests().none { it.url.contains("refresh") || it.url.contains("logout") })
        h.close()
    }

    @Test
    fun `the app can keep using its client after a verification`() = runTest {
        val h = harness()
        h.signIn()
        h.accountSession.verify(OTHER_SERVER, "good")

        val user = h.api.getCurrentUser()

        assertEquals(ORIGINAL_USER_ID, user.id)
        assertEquals(listOf("Bearer $ORIGINAL_TOKEN"), h.requests().last().authorization)
        assertEquals("GET $ORIGINAL_SERVER/api/v2/user", h.requests().last().toString())
        h.close()
    }

    // --- plan ---

    @Test
    fun `same server and same user keeps data and loses nothing`() = runTest {
        val f = WiperFixture().apply { dao.pending += queuedAction(1) }
        val h = harness(f)
        h.signIn()

        val plan = h.accountSession.plan(AccountIdentity(ORIGINAL_SERVER, ORIGINAL_USER_ID))

        assertEquals(LoginDataAction.KEEP, plan.action)
        assertEquals(0, plan.unsyncedActionsLost)
        h.close()
    }

    @Test
    fun `a different user reports the queued changes it would discard`() = runTest {
        val f = WiperFixture().apply {
            dao.pending += queuedAction(1)
            dao.pending += queuedAction(2, status = "failed")
        }
        val h = harness(f)
        h.signIn()

        val plan = h.accountSession.plan(AccountIdentity(ORIGINAL_SERVER, 8))

        assertEquals(LoginDataAction.WIPE_ALL, plan.action)
        assertEquals(2, plan.unsyncedActionsLost)
        h.close()
    }

    @Test
    fun `a different server wipes`() = runTest {
        val h = harness()
        h.signIn()

        assertEquals(
            LoginDataAction.WIPE_ALL,
            h.accountSession.plan(AccountIdentity(OTHER_SERVER, ORIGINAL_USER_ID)).action,
        )
        h.close()
    }

    @Test
    fun `an old session without a stored user id counts as the same account on the same server`() = runTest {
        val h = harness()
        h.signIn()
        h.storage.clear()
        h.storage.storeVikunjaUrl(ORIGINAL_SERVER)

        assertEquals(
            LoginDataAction.KEEP,
            h.accountSession.plan(AccountIdentity(ORIGINAL_SERVER, 8)).action,
        )
        h.close()
    }

    @Test
    fun `with no account stored the data is not anyone's and is wiped`() = runTest {
        val h = harness(signedIn = false)

        assertEquals(
            LoginDataAction.WIPE_ALL,
            h.accountSession.plan(AccountIdentity(ORIGINAL_SERVER, ORIGINAL_USER_ID)).action,
        )
        h.close()
    }

    // --- apply / recordIdentity ---

    @Test
    fun `applying a wipe clears everything and applying a keep touches nothing`() = runTest {
        val f = WiperFixture().apply {
            dao.taskIds += 1
            dao.pending += queuedAction(1)
            dao.addRoutineHistory("r:1")
        }
        val h = harness(f)
        val identity = AccountIdentity(ORIGINAL_SERVER, ORIGINAL_USER_ID)

        h.accountSession.apply(LoginPlan(identity, LoginDataAction.KEEP, 0))
        assertEquals(1, f.dao.pending.size)
        assertEquals(1, f.dao.routineArchive.size)

        h.accountSession.apply(LoginPlan(identity, LoginDataAction.WIPE_ALL, 1))
        assertTrue(f.dao.taskIds.isEmpty() && f.dao.pending.isEmpty() && f.dao.routineArchive.isEmpty())
        h.close()
    }

    @Test
    fun `recording the identity stores the user id`() = runTest {
        val h = harness(signedIn = false)

        h.accountSession.recordIdentity(AccountIdentity(OTHER_SERVER, 8))

        assertEquals(8L, h.storage.getUserId())
        h.close()
    }
}

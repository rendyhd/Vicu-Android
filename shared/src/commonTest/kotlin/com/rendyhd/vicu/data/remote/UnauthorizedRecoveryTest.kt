package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.auth.LoginHarness
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_SERVER
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_USER_ID
import com.rendyhd.vicu.auth.LoginHarness.Companion.unauthorized
import com.rendyhd.vicu.auth.LoginHarness.Companion.userJson
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What a 401 does to a password or SSO session (review finding C-4): when the refresh fails, the
 * stored backup token is tried whenever it is not the refused token, whatever the phone's clock
 * says about the JWT, and the session ends only when the refresh itself was refused.
 */
class UnauthorizedRecoveryTest {

    private enum class Refresh { UNAVAILABLE, REFUSED, NO_ANSWER }

    private val userPath = "/api/v2/user"
    private val refreshPath = "/api/v2/user/token/refresh"

    private fun HttpRequestData.bearer(): String? = headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

    /** The server refuses the session JWT (the phone's clock still calls it valid) and reads the user with the backup token. */
    private fun harness(refresh: Refresh): LoginHarness = LoginHarness { request ->
        server(request, refresh)
    }

    private fun MockRequestHandleScope.server(request: HttpRequestData, refresh: Refresh): HttpResponseData =
        when (request.url.encodedPath) {
            refreshPath -> when (refresh) {
                Refresh.UNAVAILABLE -> respond("", HttpStatusCode.ServiceUnavailable)
                Refresh.REFUSED -> unauthorized()
                Refresh.NO_ANSWER -> throw IllegalStateException("timed out")
            }
            userPath -> if (request.bearer() == BACKUP) userJson(ORIGINAL_USER_ID) else unauthorized()
            else -> respond("", HttpStatusCode.NotFound)
        }

    private suspend fun LoginHarness.signInWithSession(backup: Boolean) {
        storage.storeJwt(SESSION_JWT, Long.MAX_VALUE / 2)
        storage.storeRefreshToken("refresh-1")
        if (backup) storage.storeBackupApiToken(BACKUP, Long.MAX_VALUE, 41)
        storage.storeAuthMethod("password")
        storage.storeVikunjaUrl(ORIGINAL_SERVER)
        storage.storeUserId(ORIGINAL_USER_ID)
        baseUrlHolder.baseUrl = ORIGINAL_SERVER
        authManager.ensureInitializedAndGetToken()
    }

    private suspend fun LoginHarness.userCalls() = requests().filter { it.url.endsWith(userPath) }

    @Test
    fun `a refresh that gets a 503 falls back to the backup token although the JWT looks valid`() = runTest {
        val h = harness(Refresh.UNAVAILABLE)
        h.signInWithSession(backup = true)

        val user = h.api.getCurrentUser()

        assertEquals(ORIGINAL_USER_ID, user.id)
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(
            listOf(listOf("Bearer $SESSION_JWT"), listOf("Bearer $BACKUP")),
            h.userCalls().map { it.authorization },
        )
        h.close()
    }

    @Test
    fun `a refused refresh still falls back to a working backup token`() = runTest {
        val h = harness(Refresh.REFUSED)
        h.signInWithSession(backup = true)

        h.api.getCurrentUser()

        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(BACKUP, h.authManager.getBestTokenSync())
        h.close()
    }

    @Test
    fun `a refresh that fails on the network returns the 401 and keeps the session`() = runTest {
        for (refresh in listOf(Refresh.UNAVAILABLE, Refresh.NO_ANSWER)) {
            val h = harness(refresh)
            h.signInWithSession(backup = false)

            val error = assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

            assertEquals(401, error.httpStatus, "$refresh")
            assertEquals(AuthState.Authenticated, h.authManager.authState.value, "$refresh")
            assertEquals(1, h.userCalls().size, "$refresh: not sent again with the same token")
            h.close()
        }
    }

    @Test
    fun `a refused refresh without a backup token ends the session`() = runTest {
        val h = harness(Refresh.REFUSED)
        h.signInWithSession(backup = false)

        assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertEquals(AuthState.NeedsReAuth, h.authManager.authState.value)
        h.close()
    }

    @Test
    fun `the backup token is tried once - its own 401 goes back to the caller`() = runTest {
        val h = LoginHarness { request ->
            when (request.url.encodedPath) {
                refreshPath -> respond("", HttpStatusCode.ServiceUnavailable)
                userPath -> unauthorized()
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.signInWithSession(backup = true)

        val error = assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertEquals(401, error.httpStatus)
        assertEquals(2, h.userCalls().size)
        h.close()
    }

    @Test
    fun `a 401 that arrives after signing out leaves the device signed out`() = runTest {
        val h = harness(Refresh.REFUSED)
        h.signInWithSession(backup = false)
        h.authManager.logout()

        assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertEquals(AuthState.Unauthenticated, h.authManager.authState.value)
        h.authManager.setNeedsReAuth()
        assertEquals(AuthState.Unauthenticated, h.authManager.authState.value)
        assertNull(h.authManager.getBestTokenSync())
        h.close()
    }

    private companion object {
        const val SESSION_JWT = "session-jwt"
        const val BACKUP = "backup-token"
    }
}

package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.auth.LoggedRequest
import com.rendyhd.vicu.auth.LoginHarness
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_SERVER
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_TOKEN
import com.rendyhd.vicu.auth.LoginHarness.Companion.unauthorized
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.MutableTimeSource
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An API token that lacks a collaboration permission is told apart from an ended session
 * (cross-app semantics 9.7): the 401 is checked against the project list, and only a token the
 * project list refuses as well ends the session.
 */
class ApiTokenPermissionTest {

    private val base = "/api/v2/"
    private val projectCheck = "GET $ORIGINAL_SERVER/api/v2/projects?per_page=1&page=1"

    private fun MockRequestHandleScope.ok(body: String = "{}"): HttpResponseData =
        respond(content = body, status = HttpStatusCode.OK, headers = authTestJsonHeaders)

    private fun MockRequestHandleScope.projectsPage(): HttpResponseData =
        ok("""{"items":[{"id":5,"title":"Inbox"}],"total":1,"page":1,"per_page":1,"total_pages":1}""")

    private fun HttpRequestData.isProjectList() = method == HttpMethod.Get && url.encodedPath == "/api/v2/projects"

    private suspend fun LoginHarness.checks(): List<LoggedRequest> = requests().filter { it.toString() == projectCheck }

    // --- Which endpoints ---------------------------------------------------------------------

    @Test
    fun `the collaboration endpoints are recognised by whole endpoint`() {
        for (path in listOf(
            "tasks/12/comments", "tasks/12/comments/70", "tasks/12/assignees", "tasks/12/assignees/2",
            "tasks/12/read", "subscriptions/task/12", "projects/3/users", "projects/3/users/search",
            "projects/3/users/maya", "projects/3/teams", "users",
        )) {
            assertTrue(KtorClientFactory.isCollaborationPath("/api/v2/$path", base), path)
        }
        for (path in listOf(
            "user", "tasks/12", "projects", "projects/3", "projects/3/tasks", "tasks/12/labels",
            "tasks/12/attachments", "tasks/12/comments/70/extra", "users/2", "projects/3/views",
        )) {
            assertFalse(KtorClientFactory.isCollaborationPath("/api/v2/$path", base), path)
        }
        assertTrue(KtorClientFactory.isCollaborationPath("/vikunja/api/v2/tasks/12/comments", "/vikunja/api/v2/"))
    }

    @Test
    fun `Notify me is offered for password and SSO sign-ins only`() {
        assertTrue(Collaboration.canFollowTasks("password"))
        assertTrue(Collaboration.canFollowTasks("oidc"))
        assertFalse(Collaboration.canFollowTasks("api_token"))
        assertFalse(Collaboration.canFollowTasks(null))
    }

    // --- The check ---------------------------------------------------------------------------

    @Test
    fun `a comment the token may not post says what the token lacks, and the session stays`() = runTest {
        val h = LoginHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v2/tasks/12/comments" -> unauthorized()
                request.isProjectList() -> projectsPage()
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.signIn()

        val error = assertFailsWith<VikunjaApiException> { h.api.createComment(12, "<p>hi</p>") }

        assertEquals(403, error.httpStatus)
        assertTrue(error.tokenPermission)
        assertEquals(ApiTokenPermission.TOKEN_LACKS_PERMISSION, error.message)
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(ORIGINAL_TOKEN, h.storage.getApiToken())
        assertEquals(listOf(listOf("Bearer $ORIGINAL_TOKEN")), h.checks().map { it.authorization }, "checked with the same token")
        h.close()
    }

    @Test
    fun `following a task with an API token says that tokens cannot follow`() = runTest {
        val h = LoginHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v2/subscriptions/task/12" -> unauthorized()
                request.isProjectList() -> projectsPage()
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.signIn()

        val error = assertFailsWith<VikunjaApiException> { h.api.setSubscribed(12, true) }

        assertTrue(error.tokenPermission)
        assertEquals(ApiTokenPermission.TOKEN_CANNOT_FOLLOW, error.message)
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        h.close()
    }

    @Test
    fun `a token the project list refuses as well has ended the session`() = runTest {
        val h = LoginHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v2/tasks/12/assignees" -> unauthorized()
                request.isProjectList() -> unauthorized()
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.signIn()

        val error = assertFailsWith<VikunjaApiException> { h.api.addAssignee(12, 2) }

        assertEquals(401, error.httpStatus)
        assertFalse(error.tokenPermission)
        assertEquals(AuthState.NeedsReAuth, h.authManager.authState.value)
        h.close()
    }

    @Test
    fun `no answer from the project list counts as a working token`() = runTest {
        val h = LoginHarness { request ->
            when {
                request.method == HttpMethod.Put && request.url.encodedPath == "/api/v2/tasks/12/read" -> unauthorized()
                request.isProjectList() -> throw IllegalStateException("no connection")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.signIn()

        val error = assertFailsWith<VikunjaApiException> { h.api.markTaskRead(12) }

        assertTrue(error.tokenPermission)
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        h.close()
    }

    @Test
    fun `one check answers for a minute`() = runTest {
        val time = MutableTimeSource(Instant.parse("2026-10-11T09:00:00Z"))
        val h = LoginHarness(tokenCheck = ApiTokenCheck(time)) { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.startsWith("/api/v2/projects/") -> unauthorized()
                request.isProjectList() -> projectsPage()
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.signIn()

        assertFailsWith<VikunjaApiException> { h.api.searchProjectUsers(3) }
        assertFailsWith<VikunjaApiException> { h.api.searchProjectUsers(4) }
        assertEquals(1, h.checks().size, "the second refusal reuses the first check")

        time.advance(61.seconds)
        val error = assertFailsWith<VikunjaApiException> { h.api.searchProjectUsers(5) }

        assertTrue(error.tokenPermission)
        assertEquals(2, h.checks().size)
        h.close()
    }

    @Test
    fun `a 401 outside the collaboration endpoints still ends the session, unchecked`() = runTest {
        val h = LoginHarness { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/v2/user" -> unauthorized()
                request.isProjectList() -> projectsPage()
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.signIn()

        assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertEquals(AuthState.NeedsReAuth, h.authManager.authState.value)
        assertTrue(h.checks().isEmpty())
        h.close()
    }

    @Test
    fun `a password session's own token is never checked`() = runTest {
        val h = LoginHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v2/tasks/12/comments" -> unauthorized()
                request.isProjectList() -> projectsPage()
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.storage.storeJwt("session-jwt", Long.MAX_VALUE / 2)
        h.storage.storeAuthMethod("password")
        h.storage.storeVikunjaUrl(ORIGINAL_SERVER)
        h.baseUrlHolder.baseUrl = ORIGINAL_SERVER
        h.authManager.ensureInitializedAndGetToken()

        assertFailsWith<VikunjaApiException> { h.api.createComment(12, "<p>hi</p>") }

        assertTrue(h.checks().isEmpty())
        assertEquals(AuthState.NeedsReAuth, h.authManager.authState.value)
        h.close()
    }

    @Test
    fun `the backup token a password session falls back to is checked like any API token`() = runTest {
        val h = LoginHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v2/tasks/12/comments" -> unauthorized()
                request.isProjectList() -> projectsPage()
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        // The session token has expired; the app works with its backup token.
        h.storage.storeJwt("expired-jwt", 1L)
        h.storage.storeBackupApiToken("backup-token", Long.MAX_VALUE, 41)
        h.storage.storeAuthMethod("password")
        h.storage.storeVikunjaUrl(ORIGINAL_SERVER)
        h.baseUrlHolder.baseUrl = ORIGINAL_SERVER
        h.authManager.ensureInitializedAndGetToken()

        val error = assertFailsWith<VikunjaApiException> { h.api.createComment(12, "<p>hi</p>") }

        assertTrue(error.tokenPermission)
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(listOf(listOf("Bearer backup-token")), h.checks().map { it.authorization })
        h.close()
    }
}

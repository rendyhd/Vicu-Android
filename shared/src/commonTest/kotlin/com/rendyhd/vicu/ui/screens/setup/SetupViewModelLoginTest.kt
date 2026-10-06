package com.rendyhd.vicu.ui.screens.setup

import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.auth.LoginHarness
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_SERVER
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_TOKEN
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_USER_ID
import com.rendyhd.vicu.auth.LoginHarness.Companion.unauthorized
import com.rendyhd.vicu.auth.LoginHarness.Companion.userJson
import com.rendyhd.vicu.auth.OidcHandler
import com.rendyhd.vicu.auth.PasswordLoginHandler
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.WiperFixture
import com.rendyhd.vicu.data.local.queuedAction
import com.rendyhd.vicu.worker.awaitUntil
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The login paths in Setup: the credentials are verified before anything stored or local is
 * touched, and local data is wiped only when the account changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelLoginTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val projectsPage = """{"items":[{"id":5,"title":"Inbox"}],"total":1,"page":1,"per_page":100,"total_pages":1}"""

    /**
     * Tokens the server knows: "same-account" and the original token are user 7, "other-account"
     * is user 8, "jwt-other" (password login result) is user 8, anything else is rejected.
     */
    private fun fixtureWithWork() = WiperFixture().apply {
        dao.taskIds += listOf(1, 2)
        dao.pending += queuedAction(entityId = 1)
        dao.addRoutineHistory("routine-a:2026-01-01")
    }

    private fun harness(fixture: WiperFixture) = LoginHarness(fixture = fixture) { request ->
        val bearer = request.headers[HttpHeaders.Authorization]
        val path = request.url.encodedPath
        when {
            request.method == HttpMethod.Get && path.endsWith("/user") -> when (bearer) {
                "Bearer $ORIGINAL_TOKEN", "Bearer same-account", "Bearer jwt-same" -> userJson(ORIGINAL_USER_ID)
                "Bearer other-account", "Bearer jwt-other" -> userJson(8)
                else -> unauthorized()
            }
            request.method == HttpMethod.Get && path.endsWith("/projects") ->
                respond(projectsPage, HttpStatusCode.OK, authTestJsonHeaders)
            request.method == HttpMethod.Post && path.endsWith("/login") -> {
                val sameAccount = (request.body as TextContent).text.contains("\"rendy\"")
                respond(
                    """{"token":"${if (sameAccount) "jwt-same" else "jwt-other"}"}""",
                    HttpStatusCode.OK,
                    authTestJsonHeaders,
                )
            }
            // Backup-token creation and anything else the flow tries is simply unavailable.
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun LoginHarness.viewModel() = SetupViewModel(
        apiService = api,
        authManager = authManager,
        baseUrlHolder = baseUrlHolder,
        passwordLoginHandler = PasswordLoginHandler { api },
        oidcHandler = OidcHandler { api },
        accountSession = accountSession,
        platformAuthHooks = hooks,
    )

    private suspend fun SetupViewModel.awaitOutcome() = awaitUntil {
        val s = uiState.value
        s.step == SetupStep.ProjectSelection || s.error != null || s.discardPrompt != null
    }

    private fun SetupViewModel.enterToken(token: String, server: String = ORIGINAL_SERVER) {
        updateServerUrl(server)
        selectApiTokenEntry()
        updateApiToken(token)
        submitApiToken()
    }

    // --- API token ---

    @Test
    fun `a wrong API token changes nothing and does not sign the user out`() = runTest {
        val f = fixtureWithWork()
        val h = harness(f)
        h.signIn()
        val vm = h.viewModel()

        vm.enterToken("mistyped")
        vm.awaitOutcome()

        assertTrue(assertNotNull(vm.uiState.value.error).startsWith("Invalid API token"))
        assertEquals(false, vm.uiState.value.isLoading)
        assertEquals(ORIGINAL_TOKEN, h.storage.getApiToken())
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(1, f.dao.pending.size)
        assertEquals(setOf(1L, 2L), f.dao.taskIds)
        assertEquals(1, f.dao.routineArchive.size)
        assertTrue(h.requests().none { it.url.contains("logout") }, "no server logout")
        h.close()
    }

    @Test
    fun `an API token for the same account keeps the offline queue and routine history`() = runTest {
        val f = fixtureWithWork()
        val h = harness(f)
        h.signIn()
        val vm = h.viewModel()

        vm.enterToken("same-account")
        vm.awaitOutcome()

        assertEquals(SetupStep.ProjectSelection, vm.uiState.value.step)
        assertEquals("same-account", h.storage.getApiToken())
        assertEquals(ORIGINAL_USER_ID, h.storage.getUserId())
        assertEquals(1, f.dao.pending.size)
        assertEquals(setOf(1L, 2L), f.dao.taskIds)
        assertEquals(1, f.dao.routineArchive.size)
        assertEquals(0, f.customLists.clearLocalCalls)
        h.close()
    }

    @Test
    fun `an API token for another account without queued work wipes and signs in`() = runTest {
        val f = WiperFixture().apply { dao.taskIds += 1 }
        val h = harness(f)
        h.signIn()
        val vm = h.viewModel()

        vm.enterToken("other-account")
        vm.awaitOutcome()

        assertEquals(SetupStep.ProjectSelection, vm.uiState.value.step)
        assertNull(vm.uiState.value.discardPrompt)
        assertEquals("other-account", h.storage.getApiToken())
        assertEquals(8L, h.storage.getUserId())
        assertTrue(f.dao.taskIds.isEmpty())
        assertEquals(1, f.customLists.clearLocalCalls)
        h.close()
    }

    @Test
    fun `another account with queued work asks before discarding it and changes nothing until told`() = runTest {
        val f = fixtureWithWork()
        val h = harness(f)
        h.signIn()
        val vm = h.viewModel()

        vm.enterToken("other-account")
        vm.awaitOutcome()

        assertEquals(DiscardPrompt(1), vm.uiState.value.discardPrompt)
        assertEquals(false, vm.uiState.value.isLoading)
        assertEquals(ORIGINAL_TOKEN, h.storage.getApiToken(), "credentials untouched while waiting")
        assertEquals(1, f.dao.pending.size)

        vm.confirmDiscardAndSignIn()
        awaitUntil { vm.uiState.value.step == SetupStep.ProjectSelection }

        assertNull(vm.uiState.value.discardPrompt)
        assertEquals("other-account", h.storage.getApiToken())
        assertEquals(8L, h.storage.getUserId())
        assertTrue(f.dao.pending.isEmpty() && f.dao.taskIds.isEmpty() && f.dao.routineArchive.isEmpty())
        h.close()
    }

    @Test
    fun `declining to discard leaves the previous account exactly as it was`() = runTest {
        val f = fixtureWithWork()
        val h = harness(f)
        h.signIn()
        val vm = h.viewModel()

        vm.enterToken("other-account")
        vm.awaitOutcome()
        vm.cancelDiscard()

        assertNull(vm.uiState.value.discardPrompt)
        assertEquals(false, vm.uiState.value.isLoading)
        assertEquals(ORIGINAL_TOKEN, h.storage.getApiToken())
        assertEquals(ORIGINAL_USER_ID, h.storage.getUserId())
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(1, f.dao.pending.size)
        assertEquals(1, f.dao.routineArchive.size)
        h.close()
    }

    // --- password ---

    @Test
    fun `re-login with a password for the same account keeps queued work`() = runTest {
        val f = fixtureWithWork()
        val h = harness(f)
        h.signIn()
        val vm = h.viewModel()

        vm.updateServerUrl(ORIGINAL_SERVER)
        vm.selectPasswordLogin()
        vm.updateUsername("rendy")
        vm.updatePassword("secret")
        vm.submitPasswordLogin()
        vm.awaitOutcome()

        assertEquals(SetupStep.ProjectSelection, vm.uiState.value.step)
        assertEquals("jwt-same", h.storage.getJwt())
        assertEquals(ORIGINAL_USER_ID, h.storage.getUserId())
        assertEquals(1, f.dao.pending.size)
        assertEquals(1, f.dao.routineArchive.size)
        // The new session was verified with its own token before it was stored.
        val verify = h.requests().first { it.url.endsWith("/user") }
        assertEquals(listOf("Bearer jwt-same"), verify.authorization)
        h.close()
    }

    @Test
    fun `password login as another account asks before discarding queued work`() = runTest {
        val f = fixtureWithWork()
        val h = harness(f)
        h.signIn()
        val vm = h.viewModel()

        vm.updateServerUrl(ORIGINAL_SERVER)
        vm.selectPasswordLogin()
        vm.updateUsername("someone-else")
        vm.updatePassword("secret")
        vm.submitPasswordLogin()
        vm.awaitOutcome()

        assertEquals(DiscardPrompt(1), vm.uiState.value.discardPrompt)
        assertNull(h.storage.getJwt())
        assertEquals(1, f.dao.pending.size)
        h.close()
    }
}

package com.rendyhd.vicu.ui.screens.setup

import com.rendyhd.vicu.auth.LoginHarness
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_SERVER
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_TOKEN
import com.rendyhd.vicu.auth.LoginHarness.Companion.userJson
import com.rendyhd.vicu.auth.OidcHandler
import com.rendyhd.vicu.auth.PasswordLoginHandler
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.worker.awaitUntil
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Setup shows first and how Back steps through it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelStepsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val projectsPage = """{"items":[{"id":5,"title":"Inbox"}],"total":1,"page":1,"per_page":100,"total_pages":1}"""

    private fun harness() = LoginHarness { request ->
        val path = request.url.encodedPath
        when {
            request.method == HttpMethod.Get && path.endsWith("/user") -> userJson(7)
            request.method == HttpMethod.Get && path.endsWith("/projects") ->
                respond(projectsPage, HttpStatusCode.OK, authTestJsonHeaders)
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
        syncStaleness = fixture.staleness,
        repositoryHooks = fixture.hooks,
    )

    @Test
    fun `a stored server URL is prefilled so re-authentication does not retype it`() = runTest {
        val h = harness()
        h.signIn()
        val vm = h.viewModel()

        awaitUntil { vm.uiState.value.serverUrl.isNotEmpty() }

        assertEquals(ORIGINAL_SERVER, vm.uiState.value.serverUrl)
        h.close()
    }

    @Test
    fun `a fresh install starts with an empty server URL`() = runTest {
        val h = harness()
        val vm = h.viewModel()

        assertEquals("", vm.uiState.value.serverUrl)
        h.close()
    }

    @Test
    fun `a plain http address to a public host raises the cleartext warning but does not block`() = runTest {
        val h = harness()
        val vm = h.viewModel()

        vm.updateServerUrl("http://tasks.example.com")
        assertTrue(vm.uiState.value.showCleartextWarning)

        vm.updateServerUrl("http://192.168.1.20:3456")
        assertFalse(vm.uiState.value.showCleartextWarning)

        vm.updateServerUrl("https://tasks.example.com")
        assertFalse(vm.uiState.value.showCleartextWarning)
        h.close()
    }

    @Test
    fun `back steps from a login form to the method picker and then to the server step`() = runTest {
        val h = harness()
        val vm = h.viewModel()

        vm.selectPasswordLogin()
        assertTrue(vm.uiState.value.canGoBack)
        vm.goBack()
        assertEquals(SetupStep.AuthMethodPicker, vm.uiState.value.step)

        assertTrue(vm.uiState.value.canGoBack)
        vm.goBack()
        assertEquals(SetupStep.ServerUrl, vm.uiState.value.step)
        assertFalse(vm.uiState.value.canGoBack, "nothing precedes the server step")
        h.close()
    }

    @Test
    fun `back does not return to the method picker once the sign-in has gone through`() = runTest {
        val h = harness()
        h.signIn()
        val vm = h.viewModel()
        vm.updateServerUrl(ORIGINAL_SERVER)
        vm.selectApiTokenEntry()
        vm.updateApiToken(ORIGINAL_TOKEN)
        vm.submitApiToken()
        awaitUntil { vm.uiState.value.step == SetupStep.ProjectSelection }

        assertFalse(vm.uiState.value.canGoBack)
        vm.goBack()

        assertEquals(SetupStep.ProjectSelection, vm.uiState.value.step)
        h.close()
    }
}

package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which requests go out without the Authorization header, and how the API's JSON is read. */
class KtorClientFactoryAuthPathsTest {

    private val base = "/api/v2/"

    @Test
    fun `the sign-in, server info, refresh and OIDC endpoints are exempt`() {
        assertTrue(KtorClientFactory.isAuthExemptPath("/api/v2/login", base))
        assertTrue(KtorClientFactory.isAuthExemptPath("/api/v2/info", base))
        assertTrue(KtorClientFactory.isAuthExemptPath("/api/v2/user/token/refresh", base))
        assertTrue(KtorClientFactory.isAuthExemptPath("/api/v2/auth/openid/google/callback", base))
    }

    @Test
    fun `a server in a sub-path is recognised through its base path`() {
        val prefixed = "/vikunja/api/v2/"

        assertTrue(KtorClientFactory.isAuthExemptPath("/vikunja/api/v2/info", prefixed))
        assertFalse(KtorClientFactory.isAuthExemptPath("/vikunja/api/v2/projects/3/info", prefixed))
    }

    @Test
    fun `a path that only contains an exempt name is not exempt`() {
        assertFalse(KtorClientFactory.isAuthExemptPath("/api/v2/projects/3/info", base))
        assertFalse(KtorClientFactory.isAuthExemptPath("/api/v2/tasks/login", base))
        assertFalse(KtorClientFactory.isAuthExemptPath("/api/v2/information", base))
        assertFalse(KtorClientFactory.isAuthExemptPath("/api/v2/loginhelp", base))
        assertFalse(KtorClientFactory.isAuthExemptPath("/api/v2/user/token/refreshing", base))
        assertFalse(KtorClientFactory.isAuthExemptPath("/api/v2/tasks/5/attachments/auth/openid", base))
    }

    @Test
    fun `before a server is configured the bare endpoint path is matched`() {
        assertTrue(KtorClientFactory.isAuthExemptPath("/info", ""))
        assertFalse(KtorClientFactory.isAuthExemptPath("/projects/info", ""))
    }

    @Test
    fun `only the refresh endpoint itself is a token refresh`() {
        assertTrue(KtorClientFactory.isTokenRefreshPath("/api/v2/user/token/refresh", base))
        assertFalse(KtorClientFactory.isTokenRefreshPath("/api/v2/info", base))
        assertFalse(KtorClientFactory.isTokenRefreshPath("/api/v2/tasks/user/token/refresh", base))
    }

    // ---- through a real client ---------------------------------------------------------------

    @Test
    fun `requests carry the token unless the endpoint is exempt`() = runTest {
        val storage = InMemoryTokenStorage()
        val authManager = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = storage,
            apiServiceProvider = { error("not used") },
            appScope = backgroundScope,
            networkMonitor = FakeNetworkMonitor(),
        )
        authManager.onApiTokenLogin("secret-token", "https://vikunja.test")
        val baseUrlHolder = BaseUrlHolder(storage).apply { baseUrl = "https://vikunja.test" }
        val authHeaders = mutableMapOf<String, String?>()
        val client = KtorClientFactory.create(
            engine = MockEngine { request ->
                authHeaders[request.url.encodedPath] = request.headers[HttpHeaders.Authorization]
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            json = createApiJson(),
            baseUrlHolder = baseUrlHolder,
            authManager = authManager,
        )

        client.get("info")
        client.get("projects/3/info")
        client.get("information")
        client.get("tasks/5")

        assertNull(authHeaders["/api/v2/info"], "the server info is public")
        assertEquals("Bearer secret-token", authHeaders["/api/v2/projects/3/info"])
        assertEquals("Bearer secret-token", authHeaders["/api/v2/information"])
        assertEquals("Bearer secret-token", authHeaders["/api/v2/tasks/5"])
    }

    // ---- JSON --------------------------------------------------------------------------------

    @Test
    fun `a null where the app expects a value takes the field's default instead of failing`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"id":7,"title":null,"priority":null,"done":null,"project_id":3,"description":null}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(createApiJson()) }
        }

        val task = VikunjaApiService(client, createApiJson()).getTask(7)

        assertEquals(7L, task.id)
        assertEquals("", task.title)
        assertEquals(0, task.priority)
        assertEquals(false, task.done)
        assertEquals("", task.description)
        assertEquals(3L, task.projectId, "values that are there are kept")
    }

    @Test
    fun `unknown fields are still ignored`() {
        val task = createApiJson().decodeFromString(
            TaskDto.serializer(),
            """{"id":1,"title":"x","a_field_added_by_a_newer_server":{"nested":true}}""",
        )

        assertEquals("x", task.title)
    }
}

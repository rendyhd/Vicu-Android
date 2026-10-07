package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.local.WiperFixture
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.data.remote.KtorClientFactory
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One request as the server saw it: method, full URL and every Authorization header value. */
data class LoggedRequest(val method: String, val url: String, val authorization: List<String>) {
    override fun toString(): String = "$method $url"
}

/**
 * The production HTTP pipeline (base-URL redirection, Authorization injection, 401 refresh)
 * over a mock engine, with an [AuthManager], [AccountSession] and fakes for local data. A device
 * that is signed in to [ORIGINAL_SERVER] as user [ORIGINAL_USER_ID] with an API token, unless
 * [signedIn] is false.
 */
class LoginHarness(
    signedIn: Boolean = true,
    val fixture: WiperFixture = WiperFixture(),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val storage = InMemoryTokenStorage()
    val hooks = RecordingAuthHooks()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val log = mutableListOf<LoggedRequest>()

    suspend fun requests(): List<LoggedRequest> = lock.withLock { log.toList() }

    private val service: VikunjaApiService

    val baseUrlHolder = BaseUrlHolder(storage)
    val authManager = AuthManager(
        platformAuthHooks = hooks,
        tokenStorage = storage,
        apiServiceProvider = { service },
        appScope = scope,
        networkMonitor = FakeNetworkMonitor(),
    )

    init {
        val client = KtorClientFactory.create(
            engine = MockEngine { request ->
                lock.withLock {
                    log += LoggedRequest(
                        request.method.value,
                        request.url.toString(),
                        request.headers.getAll(HttpHeaders.Authorization).orEmpty(),
                    )
                }
                handler(request)
            },
            json = authTestJson,
            baseUrlHolder = baseUrlHolder,
            authManager = authManager,
        )
        service = VikunjaApiService(client, authTestJson)
    }

    val api: VikunjaApiService get() = service
    val accountSession = AccountSession(storage, service, fixture.wiper)
    val sessionCleanup = SessionCleanup(authManager, fixture.wiper)

    /** Puts the device in the state of an existing session; call from a coroutine. */
    suspend fun signIn() {
        storage.storeApiToken(ORIGINAL_TOKEN, Long.MAX_VALUE)
        storage.storeAuthMethod("api_token")
        storage.storeVikunjaUrl(ORIGINAL_SERVER)
        storage.storeUserId(ORIGINAL_USER_ID)
        storage.storeInboxProjectId(5)
        baseUrlHolder.baseUrl = ORIGINAL_SERVER
        authManager.ensureInitializedAndGetToken()
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        const val ORIGINAL_SERVER = "https://tasks.example.com"
        const val ORIGINAL_TOKEN = "original-token"
        const val ORIGINAL_USER_ID = 7L
        const val OTHER_SERVER = "https://other.example.com"

        fun MockRequestHandleScope.userJson(id: Long): HttpResponseData =
            respond(
                content = """{"id":$id,"username":"user$id"}""",
                status = HttpStatusCode.OK,
                headers = authTestJsonHeaders,
            )

        fun MockRequestHandleScope.unauthorized(): HttpResponseData =
            respond(
                content = """{"code":11,"message":"invalid token"}""",
                status = HttpStatusCode.Unauthorized,
                headers = authTestJsonHeaders,
            )
    }
}

package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.NetworkMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/** In-memory [TokenStorage] that mirrors the contract the platform implementations follow. */
class InMemoryTokenStorage(
    private val installId: String = "a1b2c3",
) : TokenStorage {
    private var jwt: String? = null
    private var jwtExpiry: Long = 0L
    private var apiToken: String? = null
    private var apiTokenExpiry: Long = 0L
    private var backupTokenId: Long? = null
    private var refreshToken: String? = null
    private var serverIsV2: Boolean = false
    private val authMethod = MutableStateFlow<String?>(null)
    private var providerKey: String? = null
    private val vikunjaUrl = MutableStateFlow<String?>(null)
    private var inboxProjectId: Long? = null
    private var userId: Long? = null

    override suspend fun storeJwt(jwt: String, expiry: Long) {
        this.jwt = jwt
        this.jwtExpiry = expiry
    }

    override suspend fun getJwt(): String? = jwt
    override suspend fun getJwtExpiry(): Long = jwtExpiry

    override suspend fun storeApiToken(token: String, expiry: Long) {
        apiToken = token
        apiTokenExpiry = expiry
        backupTokenId = null
    }

    override suspend fun storeBackupApiToken(token: String, expiry: Long, tokenId: Long) {
        apiToken = token
        apiTokenExpiry = expiry
        backupTokenId = tokenId
    }

    override suspend fun getApiToken(): String? = apiToken
    override suspend fun getApiTokenExpiry(): Long = apiTokenExpiry
    override suspend fun getBackupApiTokenId(): Long? = backupTokenId
    override suspend fun hasApiToken(): Boolean = apiToken != null

    override suspend fun getInstallId(): String = installId

    override suspend fun storeRefreshToken(token: String) {
        refreshToken = token
    }

    override suspend fun getRefreshToken(): String? = refreshToken

    override suspend fun storeServerIsV2(isV2: Boolean) {
        serverIsV2 = isV2
    }

    override suspend fun getServerIsV2(): Boolean = serverIsV2

    override suspend fun storeAuthMethod(method: String) {
        authMethod.value = method
    }

    override suspend fun getAuthMethod(): String? = authMethod.value
    override val authMethodFlow: Flow<String?> = authMethod

    override suspend fun storeProviderKey(key: String) {
        providerKey = key
    }

    override suspend fun getProviderKey(): String? = providerKey

    override suspend fun storeVikunjaUrl(url: String) {
        vikunjaUrl.value = url
    }

    override suspend fun getVikunjaUrl(): String? = vikunjaUrl.value
    override val vikunjaUrlFlow: Flow<String?> = vikunjaUrl

    override suspend fun storeInboxProjectId(id: Long) {
        inboxProjectId = id
    }

    override suspend fun getInboxProjectId(): Long? = inboxProjectId

    override suspend fun storeUserId(id: Long) {
        userId = id
    }

    override suspend fun getUserId(): Long? = userId

    /** Like the real stores, [clear] keeps the install id: it identifies the install, not the session. */
    override suspend fun clear() {
        jwt = null
        jwtExpiry = 0L
        apiToken = null
        apiTokenExpiry = 0L
        backupTokenId = null
        refreshToken = null
        serverIsV2 = false
        authMethod.value = null
        providerKey = null
        vikunjaUrl.value = null
        inboxProjectId = null
        userId = null
    }
}

class RecordingAuthHooks : PlatformAuthHooks {
    var cancelCount = 0
        private set

    override fun cancelRefreshScheduler() {
        cancelCount++
    }

    override fun scheduleRefresh() = Unit
    override fun updateWidgets() = Unit
}

class FakeNetworkMonitor(online: Boolean = true) : NetworkMonitor {
    private val state = MutableStateFlow(online)
    override val isOnline: StateFlow<Boolean> = state.asStateFlow()
}

val authTestJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

val authTestJsonHeaders: Headers = Headers.build {
    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
}

fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(content = body, status = status, headers = authTestJsonHeaders)

fun HttpRequestData.describe(): String = "${method.value} ${url.encodedPath}"

/** Request counter shared between the test body and the mock engine's thread. */
class Counter {
    @Volatile
    var value: Int = 0
        private set

    fun increment(): Int {
        value += 1
        return value
    }
}

/** Append-only log that the mock engine's thread writes and the test body reads. */
class Recorder {
    @Volatile
    var items: List<String> = emptyList()
        private set

    fun add(item: String) {
        items = items + item
    }
}

/**
 * Waits in real time (not virtual time) until [condition] holds. Needed for work that the
 * mock engine finishes on its own thread after the test scheduler has gone idle.
 */
suspend fun awaitCondition(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(5)
        }
    }
}

class AuthHarness(
    val manager: AuthManager,
    val storage: InMemoryTokenStorage,
    val hooks: RecordingAuthHooks,
)

/**
 * Builds an [AuthManager] whose API calls are answered by [handler]. [appScope] should be the
 * test's `backgroundScope` so the manager's refresh timers run on virtual time.
 */
fun authHarness(
    appScope: CoroutineScope,
    storage: InMemoryTokenStorage = InMemoryTokenStorage(),
    deviceTitle: String = "Vicu — Pixel 8",
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): AuthHarness {
    val client = HttpClient(MockEngine { request -> handler(request) }) {
        install(ContentNegotiation) { json(authTestJson) }
    }
    val service = VikunjaApiService(client, authTestJson)
    val hooks = RecordingAuthHooks()
    val manager = AuthManager(
        platformAuthHooks = hooks,
        tokenStorage = storage,
        apiServiceProvider = { service },
        appScope = appScope,
        networkMonitor = FakeNetworkMonitor(),
        deviceTokenTitle = { deviceTitle },
    )
    return AuthHarness(manager, storage, hooks)
}

/** A structurally valid, unsigned JWT whose `exp` claim is [secondsFromNow] in the future. */
@OptIn(ExperimentalEncodingApi::class)
fun jwtExpiringIn(secondsFromNow: Long): String {
    val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val header = encoder.encode("""{"alg":"none"}""".encodeToByteArray())
    val exp = Clock.System.now().epochSeconds + secondsFromNow
    val payload = encoder.encode("""{"exp":$exp}""".encodeToByteArray())
    return "$header.$payload.sig"
}

package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.ApiTokenRequestDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.Base64Decoder
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.getDeviceTokenTitle
import com.rendyhd.vicu.util.isNetworkFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

enum class AuthState {
    Loading,
    Authenticated,
    NeedsReAuth,
    Unauthenticated,
}

sealed class RefreshFailure {
    data object NoRefreshToken : RefreshFailure()
    data object Unauthorized : RefreshFailure()
    data class RateLimited(val retryAfterSecs: Long) : RefreshFailure()
    data object ServerError : RefreshFailure()
    data object NetworkError : RefreshFailure()
    data object EmptyTokenReturned : RefreshFailure()
}

sealed class RefreshResult {
    data object Success : RefreshResult()
    data class Failure(val kind: RefreshFailure) : RefreshResult()
}

internal object RefreshBackoffPolicy {
    const val BASE_MS = 5_000L
    const val CAP_MS = 120_000L
    const val RATE_LIMITED_FLOOR_MS = 60_000L
    const val TERMINAL_MS = Long.MAX_VALUE / 2

    fun nextDelayMs(failure: RefreshFailure, consecutiveFailures: Int): Long = when (failure) {
        is RefreshFailure.RateLimited -> maxOf(failure.retryAfterSecs * 1000L, RATE_LIMITED_FLOOR_MS)
        RefreshFailure.Unauthorized, RefreshFailure.NoRefreshToken -> TERMINAL_MS
        else -> minOf(BASE_MS * (1L shl consecutiveFailures.coerceAtMost(5)), CAP_MS)
    }
}

/**
 * Failures thrown while talking to the refresh endpoint (no HTTP status to look at): transport
 * problems are [RefreshFailure.NetworkError], anything else is [RefreshFailure.ServerError].
 */
fun classifyRefreshException(e: Exception): RefreshFailure =
    if (isNetworkFailure(e)) RefreshFailure.NetworkError else RefreshFailure.ServerError

class AuthManager(
    private val platformAuthHooks: PlatformAuthHooks,
    private val tokenStorage: TokenStorage,
    private val apiServiceProvider: () -> VikunjaApiService,
    private val appScope: CoroutineScope,
    private val networkMonitor: NetworkMonitor,
    private val deviceTokenTitle: () -> String = { getDeviceTokenTitle() },
) {
    companion object {
        private const val TAG = "AuthManager"
        private const val JWT_EXPIRY_BUFFER_SECS = 60L
        private const val PROACTIVE_REFRESH_AHEAD_SECS = 120L
        private const val TOKEN_REVOKE_TIMEOUT_MS = 5_000L
    }

    private val _authState = MutableStateFlow(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    @Volatile
    var cachedToken: String? = null
        private set

    @Volatile
    var isServerV2Cached: Boolean = false
        private set

    @Volatile
    var cachedJwtExpiry: Long = 0L
        private set

    private val refreshMutex = Mutex()
    private val initMutex = Mutex()
    private var proactiveRefreshJob: Job? = null

    @Volatile
    private var isInitialized = false

    private data class BackoffState(val nextAllowedAtMs: Long = 0L, val consecutiveFailures: Int = 0)
    
    @Volatile
    private var refreshBackoff = BackoffState()

    init {
        appScope.launch {
            networkMonitor.isOnline
                .drop(1)
                .collect { online ->
                    if (online) {
                        Logger.d("REFRESH_BACKOFF", "connectivity restored — resetting backoff")
                        resetBackoff()
                    }
                }
        }
    }

    fun canAttemptRefreshNow(): Boolean =
        Clock.System.now().toEpochMilliseconds() >= refreshBackoff.nextAllowedAtMs

    private fun applyBackoff(failure: RefreshFailure) {
        val now = Clock.System.now().toEpochMilliseconds()
        val cur = refreshBackoff
        val nextDelayMs = RefreshBackoffPolicy.nextDelayMs(failure, cur.consecutiveFailures)
        refreshBackoff = BackoffState(now + nextDelayMs, cur.consecutiveFailures + 1)
        Logger.d(
            "REFRESH_BACKOFF",
            "failure=${failure::class.simpleName} nextDelay=${nextDelayMs}ms consecutive=${cur.consecutiveFailures + 1}",
        )
    }

    fun resetBackoff() {
        refreshBackoff = BackoffState()
    }

    suspend fun ensureInitializedAndGetToken(): String? {
        if (!isInitialized) {
            initMutex.withLock {
                if (!isInitialized) {
                    initialize()
                }
            }
        }
        return cachedToken
    }

    suspend fun initialize() {
        val oldState = _authState.value.name
        Logger.d("INITIALIZE", "start (oldState=$oldState)")
        try {
            val url = tokenStorage.getVikunjaUrl()
            if (url.isNullOrBlank()) {
                Logger.d(TAG, "initialize: no Vikunja URL stored → Unauthenticated")
                _authState.value = AuthState.Unauthenticated
                isInitialized = true
                return
            }

            // API v2 is the only supported protocol. Mark existing installations migrated
            // so their stored pre-v2 compatibility flag cannot disable session refresh.
            isServerV2Cached = true
            tokenStorage.storeServerIsV2(true)

            val jwt = tokenStorage.getJwt()
            val jwtExpiry = tokenStorage.getJwtExpiry()
            val apiToken = tokenStorage.getApiToken()
            val hasRefresh = tokenStorage.getRefreshToken() != null

            Logger.d(TAG, "initialize: jwt=${jwt != null}, jwtExpired=${jwt != null && isExpired(jwtExpiry)}, apiToken=${apiToken != null}, refreshToken=$hasRefresh, isV2=$isServerV2Cached")
            
            when {
                jwt != null && !isExpired(jwtExpiry) -> {
                    Logger.d(TAG, "initialize: JWT valid → Authenticated")
                    cachedToken = jwt
                    cachedJwtExpiry = jwtExpiry
                    _authState.value = AuthState.Authenticated
                    scheduleProactiveRefresh()
                    ensureBackupApiToken()
                    ensureUserIdStored()
                }
                apiToken != null -> {
                    Logger.d(TAG, "initialize: JWT missing/expired, using API token → Authenticated")
                    cachedToken = apiToken
                    _authState.value = AuthState.Authenticated
                    ensureUserIdStored()
                }
                jwt != null -> {
                    Logger.d(TAG, "initialize: JWT expired, no API token — attempting V2 refresh")
                    cachedToken = jwt
                    cachedJwtExpiry = jwtExpiry
                    val refreshed = withRefreshLock {
                        if (cachedToken != null && !isExpired(cachedJwtExpiry)) {
                            Logger.d(
                                "INITIALIZE_REFRESH_SKIPPED",
                                "another caller already refreshed (cached JWT valid)",
                            )
                            true
                        } else {
                            performV2Refresh()
                        }
                    }
                    if (refreshed) {
                        Logger.d(TAG, "initialize: V2 refresh succeeded → Authenticated")
                        _authState.value = AuthState.Authenticated
                        scheduleProactiveRefresh()
                        ensureBackupApiToken()
                        ensureUserIdStored()
                    } else if (cachedToken != null && !isExpired(cachedJwtExpiry)) {
                        Logger.i(TAG, "initialize: V2 refresh returned false but cached JWT is valid → Authenticated")
                        _authState.value = AuthState.Authenticated
                        scheduleProactiveRefresh()
                        ensureBackupApiToken()
                        ensureUserIdStored()
                    } else {
                        Logger.w(TAG, "initialize: V2 refresh failed, no API token → NeedsReAuth")
                        cachedToken = null
                        _authState.value = AuthState.NeedsReAuth
                    }
                }
                else -> {
                    Logger.d(TAG, "initialize: no tokens at all → Unauthenticated")
                    _authState.value = AuthState.Unauthenticated
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "initialize() failed — tokens may be corrupted, forcing re-auth", e)
            _authState.value = AuthState.Unauthenticated
        }
        isInitialized = true
    }

    fun getBestTokenSync(): String? = cachedToken

    suspend fun getBestToken(): String? {
        val jwt = tokenStorage.getJwt()
        val jwtExpiry = tokenStorage.getJwtExpiry()

        if (jwt != null && !isExpired(jwtExpiry)) {
            cachedToken = jwt
            return jwt
        }

        val apiToken = tokenStorage.getApiToken()
        if (apiToken != null) {
            cachedToken = apiToken
            return apiToken
        }

        return jwt.also { cachedToken = it }
    }

    suspend fun onLoginSuccess(
        jwt: String,
        authMethod: String,
        vikunjaUrl: String,
        providerKey: String? = null,
        refreshToken: String? = null,
    ) {
        val expiry = parseJwtExpiry(jwt)
        Logger.d("LOGIN_SUCCESS", "method=$authMethod hasRefresh=${refreshToken != null}")
        tokenStorage.storeJwt(jwt, expiry)
        tokenStorage.storeAuthMethod(authMethod)
        tokenStorage.storeVikunjaUrl(vikunjaUrl)
        if (providerKey != null) {
            tokenStorage.storeProviderKey(providerKey)
        }
        if (refreshToken != null) {
            tokenStorage.storeRefreshToken(refreshToken)
        }
        cachedToken = jwt
        cachedJwtExpiry = expiry
        resetBackoff()
        _authState.value = AuthState.Authenticated

        if (refreshToken != null) {
            scheduleProactiveRefresh()
        }
    }

    suspend fun onApiTokenLogin(token: String, vikunjaUrl: String) {
        tokenStorage.storeApiToken(token, Long.MAX_VALUE)
        tokenStorage.storeAuthMethod("api_token")
        tokenStorage.storeVikunjaUrl(vikunjaUrl)
        cachedToken = token
        _authState.value = AuthState.Authenticated
    }

    suspend fun onApiTokenSaved(token: String, expiry: Long) {
        tokenStorage.storeApiToken(token, expiry)
    }

    suspend fun onJwtRenewed(newJwt: String, newRefreshToken: String? = null) {
        val expiry = parseJwtExpiry(newJwt)
        Logger.d("JWT_RENEWED", "newRefreshToken=${newRefreshToken != null}")
        tokenStorage.storeJwt(newJwt, expiry)
        if (newRefreshToken != null) {
            tokenStorage.storeRefreshToken(newRefreshToken)
        }
        cachedToken = newJwt
        cachedJwtExpiry = expiry

        // A renewed JWT means refresh works again. Clear the backoff first so the new schedule
        // is not pushed out by the floor left behind by earlier failures.
        resetBackoff()
        scheduleProactiveRefresh()
    }

    suspend fun onInboxProjectSelected(projectId: Long) {
        tokenStorage.storeInboxProjectId(projectId)
    }

    suspend fun storeServerIsV2(isV2: Boolean) {
        isServerV2Cached = isV2
        tokenStorage.storeServerIsV2(isV2)
    }

    suspend fun getVikunjaUrl(): String? = tokenStorage.getVikunjaUrl()

    suspend fun getInboxProjectId(): Long? = tokenStorage.getInboxProjectId()

    suspend fun getRefreshToken(): String? = tokenStorage.getRefreshToken()

    suspend fun logout() {
        Logger.d("LOGOUT", "user-initiated logout")
        proactiveRefreshJob?.cancel()
        proactiveRefreshJob = null
        platformAuthHooks.cancelRefreshScheduler()
        // Revoke while the session is still valid: the token endpoints need the JWT.
        revokeBackupApiToken()
        try {
            apiServiceProvider().serverLogout()
        } catch (e: Exception) {
            Logger.d(TAG, "Server logout failed (non-fatal): ${e.message}")
        }
        cachedToken = null
        cachedJwtExpiry = 0L
        isInitialized = false
        resetBackoff()
        tokenStorage.clear()
        _authState.value = AuthState.Unauthenticated
    }

    fun setNeedsReAuth() {
        val oldState = _authState.value.name
        Logger.d("AuthStateChanged", "from $oldState to NeedsReAuth due to all token options exhausted")
        proactiveRefreshJob?.cancel()
        proactiveRefreshJob = null
        cachedToken = null
        _authState.value = AuthState.NeedsReAuth
    }

    suspend fun <T> withRefreshLock(block: suspend () -> T): T {
        return refreshMutex.withLock { block() }
    }

    fun scheduleProactiveRefresh() {
        proactiveRefreshJob?.cancel()
        val expiry = cachedJwtExpiry
        if (expiry <= 0L) return

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val expiryMs = expiry * 1000L
        val targetFireMs = expiryMs - PROACTIVE_REFRESH_AHEAD_SECS * 1000L
        val backoffFloorMs = refreshBackoff.nextAllowedAtMs
        val fireAtMs = maxOf(targetFireMs, backoffFloorMs)
        val delayMs = (fireAtMs - nowMs).coerceAtLeast(0L)

        Logger.d(
            "PROACTIVE_REFRESH_SCHEDULED",
            "willFireIn=${delayMs / 1000}s (${delayMs / 60_000}min)",
        )

        proactiveRefreshJob = appScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            val result = withRefreshLock { performV2RefreshTyped() }
            Logger.d("PROACTIVE_REFRESH_RESULT", "proactive refresh success: ${result is RefreshResult.Success}")
        }
    }

    suspend fun performV2Refresh(): Boolean = performV2RefreshTyped() is RefreshResult.Success

    suspend fun performV2RefreshTyped(): RefreshResult {
        val refreshToken = tokenStorage.getRefreshToken()
        if (refreshToken == null) {
            Logger.w(TAG, "No refresh token available for v2 refresh")
            applyBackoff(RefreshFailure.NoRefreshToken)
            return RefreshResult.Failure(RefreshFailure.NoRefreshToken)
        }
        return try {
            val cookie = RefreshCookieExtractor.buildCookieHeader(refreshToken)
            val response = apiServiceProvider().refreshToken(cookie)
            when {
                response.isSuccessful -> {
                    val newJwt = response.body()?.token.orEmpty()
                    if (newJwt.isNotBlank()) {
                        val newRefreshToken = RefreshCookieExtractor.extractRefreshToken(response)
                        onJwtRenewed(newJwt, newRefreshToken)
                        Logger.d(TAG, "V2 refresh succeeded")
                        RefreshResult.Success
                    } else {
                        Logger.w(TAG, "V2 refresh returned empty token")
                        applyBackoff(RefreshFailure.EmptyTokenReturned)
                        RefreshResult.Failure(RefreshFailure.EmptyTokenReturned)
                    }
                }
                response.code() == 401 || response.code() == 403 -> {
                    Logger.w(TAG, "V2 refresh unauthorized: HTTP ${response.code()}")
                    applyBackoff(RefreshFailure.Unauthorized)
                    RefreshResult.Failure(RefreshFailure.Unauthorized)
                }
                response.code() == 429 -> {
                    val retryAfter = response.headers["Retry-After"]?.firstOrNull()?.toLongOrNull() ?: 60L
                    Logger.w(TAG, "V2 refresh rate limited: HTTP 429, Retry-After=${retryAfter}s")
                    applyBackoff(RefreshFailure.RateLimited(retryAfter))
                    RefreshResult.Failure(RefreshFailure.RateLimited(retryAfter))
                }
                else -> {
                    Logger.w(TAG, "V2 refresh failed: HTTP ${response.code()}")
                    applyBackoff(RefreshFailure.ServerError)
                    RefreshResult.Failure(RefreshFailure.ServerError)
                }
            }
        } catch (e: CancellationException) {
            // The caller gave up (for example the proactive refresh job was replaced). That is
            // not a server failure and must not start a backoff window.
            throw e
        } catch (e: Exception) {
            val failure = classifyRefreshException(e)
            Logger.w(TAG, "V2 refresh exception (${failure::class.simpleName}): ${e.message}")
            applyBackoff(failure)
            RefreshResult.Failure(failure)
        }
    }

    suspend fun createBackupApiToken(): Boolean {
        return try {
            val installId = tokenStorage.getInstallId()
            val title = ApiTokenTitle.build(deviceTokenTitle(), installId)
            Logger.d(TAG, "fetching /routes for permissions map")
            val routes = apiServiceProvider().getApiTokenRoutes()
            val permissions: Map<String, List<String>> = routes.mapValues { (_, routeMap) ->
                routeMap.keys.toList()
            }
            if (permissions.isEmpty()) {
                Logger.w(TAG, "Routes endpoint returned no groups — cannot build permissions")
                return false
            }

            val expiry = Clock.System.now().plus(365L * 24 * 60 * 60, DateTimeUnit.SECOND)
            val expiryStr = expiry.toString()
            val request = ApiTokenRequestDto(
                title = title,
                expiresAt = expiryStr,
                permissions = permissions,
            )
            val response = apiServiceProvider().createApiToken(request)
            if (response.token.isNotBlank()) {
                val newTokenId = response.id
                tokenStorage.storeBackupApiToken(response.token, expiry.epochSeconds, newTokenId)
                Logger.i(TAG, "Backup API token created successfully (${permissions.size} groups)")
                cleanupSiblingTokens(installId, newTokenId)
                true
            } else {
                Logger.w(TAG, "Backup API token creation returned empty token")
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Backup API token creation failed: ${e.message}")
            false
        }
    }

    /**
     * Deletes older backup tokens left behind by this install (for example after a keystore
     * reset made the stored token unreadable). Only tokens carrying this install's id are
     * touched; see [ApiTokenTitle] for how pre-suffix tokens are treated.
     */
    private fun cleanupSiblingTokens(installId: String, newTokenId: Long) {
        appScope.launch {
            try {
                val api = apiServiceProvider()
                val siblings = ApiTokenTitle.siblingIds(api.listApiTokens(), installId, newTokenId)
                for (siblingId in siblings) {
                    try {
                        api.deleteApiToken(siblingId)
                        Logger.d("TOKEN_CLEANUP", "deleted sibling id=$siblingId")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to delete sibling token $siblingId: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Token list/cleanup failed (non-fatal): ${e.message}")
            }
        }
    }

    /**
     * Best-effort revocation of the backup token this app created, so a signed-out device does
     * not leave a year-long, full-access credential behind on the server. Never throws for
     * network or server problems and gives up after [TOKEN_REVOKE_TIMEOUT_MS] so logout is not
     * held up. A token the user entered manually has no stored id and is never touched.
     */
    private suspend fun revokeBackupApiToken() {
        val tokenId = try {
            tokenStorage.getBackupApiTokenId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Could not read backup token id: ${e.message}")
            null
        } ?: return

        try {
            val finished = withTimeoutOrNull(TOKEN_REVOKE_TIMEOUT_MS) {
                apiServiceProvider().deleteApiToken(tokenId)
                true
            }
            if (finished == null) {
                Logger.w(TAG, "Backup token revocation timed out; the token stays on the server until it expires")
            } else {
                Logger.d("TOKEN_REVOKE", "deleted backup token id=$tokenId")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VikunjaApiException) {
            if (e.httpStatus == 404) {
                Logger.d("TOKEN_REVOKE", "backup token id=$tokenId was already gone")
            } else {
                Logger.w(TAG, "Backup token revocation failed (HTTP ${e.httpStatus}); it stays valid until it expires")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Backup token revocation failed: ${e.message}")
        }
    }

    /**
     * Records who the signed-in account is for sessions that predate the stored user id, so a
     * later re-login can tell "same account again" (keep the offline queue) from "someone else"
     * (wipe). Does nothing once an id is stored.
     */
    private fun ensureUserIdStored() {
        appScope.launch {
            try {
                if (tokenStorage.getUserId() != null) return@launch
                val user = apiServiceProvider().getCurrentUser()
                if (user.id > 0L) tokenStorage.storeUserId(user.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Could not record the user id (non-fatal): ${e.message}")
            }
        }
    }

    private fun ensureBackupApiToken() {
        appScope.launch {
            try {
                // hasApiToken() is false for a token that can no longer be decrypted, so a
                // keystore reset leads to a fresh backup token here.
                if (tokenStorage.hasApiToken()) {
                    Logger.d("BACKUP_API_TOKEN", "already exists, skipping")
                    return@launch
                }
                Logger.w(TAG, "No usable backup API token found — attempting to create one")
                createBackupApiToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Backup API token check failed: ${e.message}")
            }
        }
    }

    private fun isExpired(epochSecs: Long): Boolean {
        val now = Clock.System.now().epochSeconds
        return epochSecs <= now + JWT_EXPIRY_BUFFER_SECS
    }

    private fun parseJwtExpiry(jwt: String): Long {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return 0L
            val payloadBytes = Base64Decoder.decodeUrlSafe(parts[1])
            val payload = payloadBytes.decodeToString()
            val json = Json.parseToJsonElement(payload).jsonObject
            json["exp"]?.jsonPrimitive?.long ?: 0L
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse JWT expiry: ${e.message}")
            0L
        }
    }
}

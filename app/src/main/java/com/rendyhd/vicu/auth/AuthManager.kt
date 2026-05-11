package com.rendyhd.vicu.auth

import android.content.Context
import android.os.Build
import android.util.Log
import com.rendyhd.vicu.data.remote.api.ApiTokenRequestDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.di.ApplicationScope
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.worker.TokenRefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthState {
    Loading,
    Authenticated,
    NeedsReAuth,
    Unauthenticated,
}

sealed class RefreshFailure {
    object NoRefreshToken : RefreshFailure()
    object Unauthorized : RefreshFailure()
    data class RateLimited(val retryAfterSecs: Long) : RefreshFailure()
    object ServerError : RefreshFailure()
    object NetworkError : RefreshFailure()
    object EmptyTokenReturned : RefreshFailure()
}

sealed class RefreshResult {
    object Success : RefreshResult()
    data class Failure(val kind: RefreshFailure) : RefreshResult()
}

/**
 * Pure backoff math, extracted so it can be unit-tested without a Hilt graph.
 *
 * - Transient errors (5xx, network, empty body): exponential 5s → 120s cap, doubling per failure.
 * - 429 RateLimited: honors `Retry-After` but never less than 60s.
 * - Terminal (Unauthorized, NoRefreshToken): a very large delay so the next attempt is gated
 *   until [AuthManager.resetBackoff] is called (login, logout, online-restored).
 */
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

@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val tokenStorage: SecureTokenStorage,
    private val apiServiceProvider: dagger.Lazy<VikunjaApiService>,
    @ApplicationScope private val appScope: CoroutineScope,
    private val networkMonitor: NetworkMonitor,
) {
    companion object {
        private const val TAG = "AuthManager"
        private const val JWT_EXPIRY_BUFFER_SECS = 60L
        private const val PROACTIVE_REFRESH_AHEAD_SECS = 120L
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
    private val refreshBackoff = AtomicReference(BackoffState())

    init {
        // Reset backoff when connectivity transitions offline → online so a network blip
        // doesn't leave the user stuck behind a backoff window after their phone reconnects.
        // StateFlow already dedupes equal emissions, so we just drop the initial value
        // and listen for transitions.
        appScope.launch {
            networkMonitor.isOnline
                .drop(1)
                .collect { online ->
                    if (online) {
                        AuthDebugLog.log("REFRESH_BACKOFF", "connectivity restored — resetting backoff")
                        resetBackoff()
                    }
                }
        }
    }

    fun canAttemptRefreshNow(): Boolean =
        System.currentTimeMillis() >= refreshBackoff.get().nextAllowedAtMs

    private fun applyBackoff(failure: RefreshFailure) {
        val now = System.currentTimeMillis()
        val cur = refreshBackoff.get()
        val nextDelayMs = RefreshBackoffPolicy.nextDelayMs(failure, cur.consecutiveFailures)
        refreshBackoff.set(BackoffState(now + nextDelayMs, cur.consecutiveFailures + 1))
        AuthDebugLog.log(
            "REFRESH_BACKOFF",
            "failure=${failure::class.simpleName} nextDelay=${nextDelayMs}ms consecutive=${cur.consecutiveFailures + 1}",
        )
    }

    fun resetBackoff() {
        refreshBackoff.set(BackoffState())
    }

    /**
     * Ensure AuthManager has loaded tokens from DataStore.
     * Safe to call concurrently — uses double-checked locking via [initMutex].
     * Used by interceptors/workers that may run before MainActivity.onCreate().
     */
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
        AuthDebugLog.log("INITIALIZE", "start (oldState=$oldState)")
        try {
            val url = tokenStorage.getVikunjaUrl()
            if (url.isNullOrBlank()) {
                Log.d(TAG, "initialize: no Vikunja URL stored → Unauthenticated")
                AuthDebugLog.authStateChanged(oldState, "Unauthenticated", "no Vikunja URL stored")
                _authState.value = AuthState.Unauthenticated
                isInitialized = true
                return
            }

            isServerV2Cached = tokenStorage.getServerIsV2()

            val jwt = tokenStorage.getJwt()
            val jwtExpiry = tokenStorage.getJwtExpiry()
            val apiToken = tokenStorage.getApiToken()
            val hasRefresh = tokenStorage.getRefreshToken() != null

            Log.d(TAG, "initialize: jwt=${jwt != null}, jwtExpired=${jwt != null && isExpired(jwtExpiry)}, apiToken=${apiToken != null}, refreshToken=$hasRefresh, isV2=$isServerV2Cached")
            AuthDebugLog.tokenState(
                jwt = jwt != null,
                jwtExpired = jwt != null && isExpired(jwtExpiry),
                apiToken = apiToken != null,
                refreshToken = hasRefresh,
                isV2 = isServerV2Cached,
            )
            if (jwtExpiry > 0L) {
                AuthDebugLog.jwtExpiry(jwtExpiry)
            }

            when {
                jwt != null && !isExpired(jwtExpiry) -> {
                    Log.d(TAG, "initialize: JWT valid → Authenticated")
                    AuthDebugLog.authStateChanged(oldState, "Authenticated", "JWT valid")
                    cachedToken = jwt
                    cachedJwtExpiry = jwtExpiry
                    _authState.value = AuthState.Authenticated
                    if (isServerV2Cached) {
                        scheduleProactiveRefresh()
                    }
                    ensureBackupApiToken()
                }
                apiToken != null -> {
                    Log.d(TAG, "initialize: JWT missing/expired, using API token → Authenticated")
                    AuthDebugLog.authStateChanged(oldState, "Authenticated", "JWT missing/expired, fallback to API token")
                    cachedToken = apiToken
                    _authState.value = AuthState.Authenticated
                }
                jwt != null -> {
                    // JWT expired, no API token — try quick refresh or force re-auth
                    Log.d(TAG, "initialize: JWT expired, no API token — attempting V2 refresh")
                    AuthDebugLog.refreshAttempt("V2 (initialize, JWT expired, no API token)")
                    cachedToken = jwt
                    cachedJwtExpiry = jwtExpiry
                    val refreshed = if (isServerV2Cached) {
                        // Serialize with proactive refresh / worker / authenticator so we don't
                        // race on the single-use refresh cookie. If another caller refreshed
                        // while we were waiting for the lock, skip the redundant HTTP call.
                        withRefreshLock {
                            if (cachedToken != null && !isExpired(cachedJwtExpiry)) {
                                AuthDebugLog.log(
                                    "INITIALIZE_REFRESH_SKIPPED",
                                    "another caller already refreshed (cached JWT valid)",
                                )
                                true
                            } else {
                                performV2Refresh()
                            }
                        }
                    } else {
                        false // Legacy renewal requires a valid JWT — can't auto-recover
                    }
                    if (refreshed) {
                        Log.d(TAG, "initialize: V2 refresh succeeded → Authenticated")
                        AuthDebugLog.authStateChanged(oldState, "Authenticated", "V2 refresh succeeded during init")
                        _authState.value = AuthState.Authenticated
                        scheduleProactiveRefresh()
                        ensureBackupApiToken()
                    } else if (cachedToken != null && !isExpired(cachedJwtExpiry)) {
                        // Defense-in-depth for fix #1: even if our refresh call returned false,
                        // a concurrent refresh may have updated cachedToken/cachedJwtExpiry
                        // before we reached this branch. Don't clobber a valid session.
                        Log.i(TAG, "initialize: V2 refresh returned false but cached JWT is valid → Authenticated")
                        AuthDebugLog.authStateChanged(
                            oldState,
                            "Authenticated",
                            "V2 refresh call failed but cached JWT is valid (concurrent refresh)",
                        )
                        _authState.value = AuthState.Authenticated
                        scheduleProactiveRefresh()
                        ensureBackupApiToken()
                    } else {
                        Log.w(TAG, "initialize: V2 refresh failed, no API token → NeedsReAuth")
                        AuthDebugLog.authStateChanged(oldState, "NeedsReAuth", "V2 refresh FAILED during init, no API token")
                        cachedToken = null
                        _authState.value = AuthState.NeedsReAuth
                    }
                }
                else -> {
                    Log.d(TAG, "initialize: no tokens at all → Unauthenticated")
                    AuthDebugLog.authStateChanged(oldState, "Unauthenticated", "no tokens at all")
                    _authState.value = AuthState.Unauthenticated
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initialize() failed — tokens may be corrupted, forcing re-auth", e)
            AuthDebugLog.logError("INITIALIZE FAILED → Unauthenticated", e)
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
        AuthDebugLog.log("LOGIN_SUCCESS", "method=$authMethod hasRefresh=${refreshToken != null}")
        AuthDebugLog.jwtExpiry(expiry)
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

        if (isServerV2Cached && refreshToken != null) {
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
        AuthDebugLog.log("JWT_RENEWED", "newRefreshToken=${newRefreshToken != null}")
        AuthDebugLog.jwtExpiry(expiry)
        tokenStorage.storeJwt(newJwt, expiry)
        if (newRefreshToken != null) {
            tokenStorage.storeRefreshToken(newRefreshToken)
        }
        cachedToken = newJwt
        cachedJwtExpiry = expiry

        if (isServerV2Cached) {
            scheduleProactiveRefresh()
        }
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
        AuthDebugLog.log("LOGOUT", "user-initiated logout")
        proactiveRefreshJob?.cancel()
        proactiveRefreshJob = null
        TokenRefreshScheduler.cancel(appContext)
        // Best-effort server-side logout
        try {
            apiServiceProvider.get().serverLogout()
        } catch (e: Exception) {
            Log.d(TAG, "Server logout failed (non-fatal)", e)
        }
        cachedToken = null
        cachedJwtExpiry = 0L
        isInitialized = false
        resetBackoff()
        tokenStorage.clear()
        _authState.value = AuthState.Unauthenticated
        AuthDebugLog.authStateChanged("*", "Unauthenticated", "user-initiated logout")
    }

    fun setNeedsReAuth() {
        val oldState = _authState.value.name
        AuthDebugLog.authStateChanged(oldState, "NeedsReAuth", "all token options exhausted")
        proactiveRefreshJob?.cancel()
        proactiveRefreshJob = null
        cachedToken = null
        _authState.value = AuthState.NeedsReAuth
    }

    suspend fun <T> withRefreshLock(block: suspend () -> T): T {
        return refreshMutex.withLock { block() }
    }

    /**
     * Proactively refresh the JWT before it expires (Vikunja 2.0+).
     * Schedules a coroutine that waits until [PROACTIVE_REFRESH_AHEAD_SECS] before expiry,
     * then performs a cookie-based refresh. Clamped by the refresh backoff window so
     * repeated failures can't drive us into a tight retry loop.
     */
    fun scheduleProactiveRefresh() {
        proactiveRefreshJob?.cancel()
        val expiry = cachedJwtExpiry
        if (expiry <= 0L) return

        val nowMs = System.currentTimeMillis()
        val expiryMs = expiry * 1000L
        val targetFireMs = expiryMs - PROACTIVE_REFRESH_AHEAD_SECS * 1000L
        val backoffFloorMs = refreshBackoff.get().nextAllowedAtMs
        val fireAtMs = maxOf(targetFireMs, backoffFloorMs)
        val delayMs = (fireAtMs - nowMs).coerceAtLeast(0L)

        AuthDebugLog.log(
            "PROACTIVE_REFRESH_SCHEDULED",
            "willFireIn=${delayMs / 1000}s (${delayMs / 60_000}min)",
        )

        proactiveRefreshJob = appScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            AuthDebugLog.refreshAttempt("proactive (scheduled)")
            val result = withRefreshLock { performV2RefreshTyped() }
            AuthDebugLog.refreshResult("proactive", result is RefreshResult.Success)
        }
    }

    /**
     * Boolean-result wrapper for callers that only care about success/failure.
     * Backoff and failure typing happen inside [performV2RefreshTyped].
     */
    suspend fun performV2Refresh(): Boolean = performV2RefreshTyped() is RefreshResult.Success

    /**
     * Perform a Vikunja 2.0 cookie-based token refresh. Returns a typed result so
     * callers (and the backoff machinery) can react to specific failure categories.
     */
    suspend fun performV2RefreshTyped(): RefreshResult {
        val refreshToken = tokenStorage.getRefreshToken()
        if (refreshToken == null) {
            Log.w(TAG, "No refresh token available for v2 refresh")
            AuthDebugLog.refreshResult("V2", false, "no refresh token stored")
            applyBackoff(RefreshFailure.NoRefreshToken)
            return RefreshResult.Failure(RefreshFailure.NoRefreshToken)
        }
        return try {
            val cookie = RefreshCookieExtractor.buildCookieHeader(refreshToken)
            val response = apiServiceProvider.get().refreshToken(cookie)
            when {
                response.isSuccessful -> {
                    val newJwt = response.body()?.token.orEmpty()
                    if (newJwt.isNotBlank()) {
                        val newRefreshToken = RefreshCookieExtractor.extractRefreshToken(response)
                        onJwtRenewed(newJwt, newRefreshToken)
                        Log.d(TAG, "V2 refresh succeeded")
                        AuthDebugLog.refreshResult("V2", true)
                        resetBackoff()
                        RefreshResult.Success
                    } else {
                        Log.w(TAG, "V2 refresh returned empty token")
                        AuthDebugLog.refreshResult("V2", false, "server returned empty token")
                        applyBackoff(RefreshFailure.EmptyTokenReturned)
                        RefreshResult.Failure(RefreshFailure.EmptyTokenReturned)
                    }
                }
                response.code() == 401 || response.code() == 403 -> {
                    Log.w(TAG, "V2 refresh unauthorized: HTTP ${response.code()}")
                    AuthDebugLog.refreshResult("V2", false, "HTTP ${response.code()} (unauthorized)")
                    applyBackoff(RefreshFailure.Unauthorized)
                    RefreshResult.Failure(RefreshFailure.Unauthorized)
                }
                response.code() == 429 -> {
                    val retryAfter = response.headers()["Retry-After"]?.toLongOrNull() ?: 60L
                    Log.w(TAG, "V2 refresh rate limited: HTTP 429, Retry-After=${retryAfter}s")
                    AuthDebugLog.refreshResult("V2", false, "HTTP 429, Retry-After=${retryAfter}s")
                    applyBackoff(RefreshFailure.RateLimited(retryAfter))
                    RefreshResult.Failure(RefreshFailure.RateLimited(retryAfter))
                }
                response.code() in 500..599 -> {
                    Log.w(TAG, "V2 refresh server error: HTTP ${response.code()}")
                    AuthDebugLog.refreshResult("V2", false, "HTTP ${response.code()}")
                    applyBackoff(RefreshFailure.ServerError)
                    RefreshResult.Failure(RefreshFailure.ServerError)
                }
                else -> {
                    Log.w(TAG, "V2 refresh failed: HTTP ${response.code()}")
                    AuthDebugLog.refreshResult("V2", false, "HTTP ${response.code()}")
                    applyBackoff(RefreshFailure.ServerError)
                    RefreshResult.Failure(RefreshFailure.ServerError)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "V2 refresh network error", e)
            AuthDebugLog.logError("V2 refresh network error", e)
            applyBackoff(RefreshFailure.NetworkError)
            RefreshResult.Failure(RefreshFailure.NetworkError)
        } catch (e: Exception) {
            Log.w(TAG, "V2 refresh exception", e)
            AuthDebugLog.logError("V2 refresh exception", e)
            applyBackoff(RefreshFailure.ServerError)
            RefreshResult.Failure(RefreshFailure.ServerError)
        }
    }

    /**
     * Create a backup API token for this user and store it locally.
     *
     * The Vikunja server requires a concrete `permissions` map on token creation —
     * `{"*": "*"}` is a frontend-only shorthand. We mirror the Vikunja frontend's
     * "Full access" preset: fetch `/api/v1/routes` and expand every group to every
     * permission key.
     *
     * Returns true on success. Logs both success and failure to [AuthDebugLog]
     * so silent failures are visible in the persistent log viewer.
     */
    suspend fun createBackupApiToken(title: String = defaultTokenTitle()): Boolean {
        return try {
            AuthDebugLog.log("BACKUP_API_TOKEN", "fetching /routes for permissions map")
            val routes = apiServiceProvider.get().getApiTokenRoutes()
            // Expand "full access": every group → every permission key in that group.
            val permissions: Map<String, List<String>> = routes.mapValues { (_, routeMap) ->
                routeMap.keys.toList()
            }
            if (permissions.isEmpty()) {
                Log.w(TAG, "Routes endpoint returned no groups — cannot build permissions")
                AuthDebugLog.log("BACKUP_API_TOKEN", "FAILED: /routes returned empty map")
                return false
            }

            val expiry = Instant.now().plusSeconds(365L * 24 * 60 * 60)
            val expiryStr = DateTimeFormatter.ISO_INSTANT.format(expiry)
            val request = ApiTokenRequestDto(
                title = title,
                expiresAt = expiryStr,
                permissions = permissions,
            )
            val response = apiServiceProvider.get().createApiToken(request)
            if (response.token.isNotBlank()) {
                val newTokenId = response.id
                tokenStorage.storeApiToken(response.token, expiry.epochSecond)
                Log.i(TAG, "Backup API token created successfully (${permissions.size} groups)")
                AuthDebugLog.log(
                    "BACKUP_API_TOKEN",
                    "created successfully (${permissions.size} groups, ${permissions.values.sumOf { it.size }} perms)",
                )
                cleanupSiblingTokens(title, newTokenId)
                true
            } else {
                Log.w(TAG, "Backup API token creation returned empty token")
                AuthDebugLog.log("BACKUP_API_TOKEN", "FAILED: server returned empty token")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backup API token creation failed", e)
            AuthDebugLog.logError("BACKUP_API_TOKEN failed", e)
            false
        }
    }

    private fun defaultTokenTitle(): String {
        val device = Build.MODEL.ifBlank { Build.DEVICE }.ifBlank { "Android" }
        val manuf = Build.MANUFACTURER
            .takeIf { it.isNotBlank() && !device.startsWith(it, ignoreCase = true) }
        val suffix = if (manuf != null) "$manuf $device" else device
        return "Vicu — $suffix"
    }

    private fun cleanupSiblingTokens(title: String, newTokenId: Long) {
        appScope.launch {
            try {
                val siblings = apiServiceProvider.get().listApiTokens()
                    .filter { it.title == title && it.id != newTokenId }
                for (sibling in siblings) {
                    try {
                        apiServiceProvider.get().deleteApiToken(sibling.id)
                        AuthDebugLog.log("TOKEN_CLEANUP", "deleted sibling id=${sibling.id}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete sibling token ${sibling.id}", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token list/cleanup failed (non-fatal)", e)
            }
        }
    }

    /**
     * If authenticated but no backup API token exists, create one.
     * This self-heals the case where initial token creation failed during setup.
     * Runs in appScope so it doesn't block initialize().
     */
    private fun ensureBackupApiToken() {
        appScope.launch {
            if (tokenStorage.hasApiToken()) {
                AuthDebugLog.log("BACKUP_API_TOKEN", "already exists, skipping")
                return@launch
            }
            Log.w(TAG, "No backup API token found — attempting to create one")
            AuthDebugLog.log("BACKUP_API_TOKEN", "missing — creating new one")
            createBackupApiToken()
        }
    }

    private fun isExpired(epochSecs: Long): Boolean {
        val now = System.currentTimeMillis() / 1000
        return epochSecs <= now + JWT_EXPIRY_BUFFER_SECS
    }

    private fun parseJwtExpiry(jwt: String): Long {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return 0L
            val payload = String(
                android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
                Charsets.UTF_8,
            )
            val json = Json.parseToJsonElement(payload).jsonObject
            json["exp"]?.jsonPrimitive?.long ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JWT expiry", e)
            0L
        }
    }
}

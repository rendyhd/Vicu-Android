package com.rendyhd.vicu.auth

import android.util.Log
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthState {
    Loading,
    Authenticated,
    NeedsReAuth,
    Unauthenticated,
}

@Singleton
class AuthManager @Inject constructor(
    private val tokenStorage: SecureTokenStorage,
    private val apiServiceProvider: dagger.Lazy<VikunjaApiService>,
    @ApplicationScope private val appScope: CoroutineScope,
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
    private var proactiveRefreshJob: Job? = null

    suspend fun initialize() {
        val url = tokenStorage.getVikunjaUrl()
        if (url.isNullOrBlank()) {
            _authState.value = AuthState.Unauthenticated
            return
        }

        isServerV2Cached = tokenStorage.getServerIsV2()

        val jwt = tokenStorage.getJwt()
        val jwtExpiry = tokenStorage.getJwtExpiry()
        val apiToken = tokenStorage.getApiToken()

        when {
            jwt != null && !isExpired(jwtExpiry) -> {
                cachedToken = jwt
                cachedJwtExpiry = jwtExpiry
                _authState.value = AuthState.Authenticated
                if (isServerV2Cached) {
                    scheduleProactiveRefresh()
                }
            }
            apiToken != null -> {
                cachedToken = apiToken
                _authState.value = AuthState.Authenticated
            }
            jwt != null -> {
                // JWT expired, no API token — need re-auth or renewal
                cachedToken = jwt // still try with expired JWT, authenticator will refresh
                cachedJwtExpiry = jwtExpiry
                _authState.value = AuthState.Authenticated
            }
            else -> {
                _authState.value = AuthState.Unauthenticated
            }
        }
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
        proactiveRefreshJob?.cancel()
        proactiveRefreshJob = null
        // Best-effort server-side logout
        try {
            apiServiceProvider.get().serverLogout()
        } catch (e: Exception) {
            Log.d(TAG, "Server logout failed (non-fatal)", e)
        }
        cachedToken = null
        cachedJwtExpiry = 0L
        tokenStorage.clear()
        _authState.value = AuthState.Unauthenticated
    }

    fun setNeedsReAuth() {
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
     * then performs a cookie-based refresh.
     */
    fun scheduleProactiveRefresh() {
        proactiveRefreshJob?.cancel()
        val expiry = cachedJwtExpiry
        if (expiry <= 0L) return

        proactiveRefreshJob = appScope.launch {
            val now = System.currentTimeMillis() / 1000
            val delaySecs = expiry - now - PROACTIVE_REFRESH_AHEAD_SECS
            if (delaySecs > 0) {
                delay(delaySecs * 1000)
            }
            withRefreshLock {
                performV2Refresh()
            }
        }
    }

    /**
     * Perform a Vikunja 2.0 cookie-based token refresh.
     * Returns true if successful.
     */
    suspend fun performV2Refresh(): Boolean {
        val refreshToken = tokenStorage.getRefreshToken()
        if (refreshToken == null) {
            Log.w(TAG, "No refresh token available for v2 refresh")
            return false
        }
        return try {
            val cookie = RefreshCookieExtractor.buildCookieHeader(refreshToken)
            val response = apiServiceProvider.get().refreshToken(cookie)
            if (response.isSuccessful) {
                val body = response.body()
                val newJwt = body?.token.orEmpty()
                if (newJwt.isNotBlank()) {
                    val newRefreshToken = RefreshCookieExtractor.extractRefreshToken(response)
                    onJwtRenewed(newJwt, newRefreshToken)
                    Log.d(TAG, "V2 proactive refresh succeeded")
                    true
                } else {
                    Log.w(TAG, "V2 refresh returned empty token")
                    false
                }
            } else {
                Log.w(TAG, "V2 refresh failed: HTTP ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "V2 refresh exception", e)
            false
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

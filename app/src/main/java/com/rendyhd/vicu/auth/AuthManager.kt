package com.rendyhd.vicu.auth

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    companion object {
        private const val TAG = "AuthManager"
        private const val JWT_EXPIRY_BUFFER_SECS = 60L
    }

    private val _authState = MutableStateFlow(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    @Volatile
    var cachedToken: String? = null
        private set

    private val refreshMutex = Mutex()

    suspend fun initialize() {
        val url = tokenStorage.getVikunjaUrl()
        if (url.isNullOrBlank()) {
            _authState.value = AuthState.Unauthenticated
            return
        }

        val jwt = tokenStorage.getJwt()
        val jwtExpiry = tokenStorage.getJwtExpiry()
        val apiToken = tokenStorage.getApiToken()

        when {
            jwt != null && !isExpired(jwtExpiry) -> {
                cachedToken = jwt
                _authState.value = AuthState.Authenticated
            }
            apiToken != null -> {
                cachedToken = apiToken
                _authState.value = AuthState.Authenticated
            }
            jwt != null -> {
                // JWT expired, no API token — need re-auth or renewal
                cachedToken = jwt // still try with expired JWT, authenticator will refresh
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

    suspend fun onLoginSuccess(jwt: String, authMethod: String, vikunjaUrl: String, providerKey: String? = null) {
        val expiry = parseJwtExpiry(jwt)
        tokenStorage.storeJwt(jwt, expiry)
        tokenStorage.storeAuthMethod(authMethod)
        tokenStorage.storeVikunjaUrl(vikunjaUrl)
        if (providerKey != null) {
            tokenStorage.storeProviderKey(providerKey)
        }
        cachedToken = jwt
        _authState.value = AuthState.Authenticated
    }

    suspend fun onApiTokenLogin(token: String, vikunjaUrl: String) {
        // API tokens don't have a built-in expiry claim, store with far-future expiry
        tokenStorage.storeApiToken(token, Long.MAX_VALUE)
        tokenStorage.storeAuthMethod("api_token")
        tokenStorage.storeVikunjaUrl(vikunjaUrl)
        cachedToken = token
        _authState.value = AuthState.Authenticated
    }

    suspend fun onApiTokenSaved(token: String, expiry: Long) {
        tokenStorage.storeApiToken(token, expiry)
    }

    suspend fun onJwtRenewed(newJwt: String) {
        val expiry = parseJwtExpiry(newJwt)
        tokenStorage.storeJwt(newJwt, expiry)
        cachedToken = newJwt
    }

    suspend fun onInboxProjectSelected(projectId: Long) {
        tokenStorage.storeInboxProjectId(projectId)
    }

    suspend fun getVikunjaUrl(): String? = tokenStorage.getVikunjaUrl()

    suspend fun getInboxProjectId(): Long? = tokenStorage.getInboxProjectId()

    suspend fun logout() {
        cachedToken = null
        tokenStorage.clear()
        _authState.value = AuthState.Unauthenticated
    }

    fun setNeedsReAuth() {
        cachedToken = null
        _authState.value = AuthState.NeedsReAuth
    }

    suspend fun <T> withRefreshLock(block: suspend () -> T): T {
        return refreshMutex.withLock { block() }
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

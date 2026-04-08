package com.rendyhd.vicu.data.remote.interceptor

import android.util.Log
import com.rendyhd.vicu.BuildConfig
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.RefreshCookieExtractor
import com.rendyhd.vicu.auth.SecureTokenStorage
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenAuthenticator @Inject constructor(
    private val authManager: AuthManager,
    private val apiServiceProvider: dagger.Lazy<VikunjaApiService>,
    private val tokenStorage: SecureTokenStorage,
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val MAX_RETRIES = 2
        private const val REFRESH_TIMEOUT_MS = 15_000L
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        val path = response.request.url.encodedPath
        val retryCount = responseCount(response)
        AuthDebugLog.log("401_RECEIVED", "path=$path retry=$retryCount/${MAX_RETRIES}")

        if (retryCount >= MAX_RETRIES) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Max retries reached, giving up (request fails but user stays logged in)")
            AuthDebugLog.log("401_MAX_RETRIES", "giving up on path=$path (user stays logged in)")
            return null
        }

        return runBlocking {
            val result = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                authManager.withRefreshLock {
                    // Check if another thread already refreshed the token
                    val currentToken = authManager.getBestTokenSync()
                    val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

                    if (currentToken != null && currentToken != failedToken) {
                        Log.d(TAG, "Another thread already refreshed the token")
                        AuthDebugLog.log("401_ALREADY_REFRESHED", "another thread refreshed token")
                        return@withRefreshLock response.request.newBuilder()
                            .header("Authorization", "Bearer $currentToken")
                            .build()
                    }

                    // Try refresh based on server version
                    if (authManager.isServerV2Cached) {
                        AuthDebugLog.refreshAttempt("V2 (authenticator 401)")
                        val v2Result = tryV2Refresh(response)
                        if (v2Result != null) {
                            Log.d(TAG, "V2 refresh succeeded in authenticator")
                            AuthDebugLog.refreshResult("V2 (authenticator)", true)
                            return@withRefreshLock v2Result
                        }
                        Log.d(TAG, "V2 refresh failed in authenticator")
                        AuthDebugLog.refreshResult("V2 (authenticator)", false)
                    } else {
                        AuthDebugLog.refreshAttempt("legacy (authenticator 401)")
                        val legacyResult = tryLegacyRefresh(response)
                        if (legacyResult != null) {
                            Log.d(TAG, "Legacy refresh succeeded in authenticator")
                            AuthDebugLog.refreshResult("legacy (authenticator)", true)
                            return@withRefreshLock legacyResult
                        }
                        Log.d(TAG, "Legacy refresh failed in authenticator")
                        AuthDebugLog.refreshResult("legacy (authenticator)", false)
                    }

                    // Fall back to API token
                    val apiToken = authManager.getBestToken()
                    if (apiToken != null && apiToken != failedToken) {
                        Log.d(TAG, "Falling back to API token in authenticator")
                        AuthDebugLog.log("401_API_TOKEN_FALLBACK", "using API token as fallback")
                        return@withRefreshLock response.request.newBuilder()
                            .header("Authorization", "Bearer $apiToken")
                            .build()
                    }

                    // All options exhausted
                    Log.w(TAG, "All token options exhausted — apiToken=${apiToken != null}, sameAsFailed=${apiToken == failedToken}")
                    AuthDebugLog.log("401_ALL_EXHAUSTED", "apiToken=${apiToken != null} sameAsFailed=${apiToken == failedToken} → NeedsReAuth")
                    authManager.setNeedsReAuth()
                    null
                }
            }
            if (result == null && retryCount < MAX_RETRIES) {
                AuthDebugLog.log("401_TIMEOUT_OR_FAIL", "refresh lock timed out or all options failed for path=$path")
            }
            result
        }
    }

    private suspend fun tryV2Refresh(response: Response): Request? {
        val refreshToken = tokenStorage.getRefreshToken()
        if (refreshToken == null) {
            Log.w(TAG, "No refresh token for v2 refresh")
            return null
        }
        return try {
            val cookie = RefreshCookieExtractor.buildCookieHeader(refreshToken)
            val refreshResponse = apiServiceProvider.get().refreshToken(cookie)
            if (refreshResponse.isSuccessful) {
                val body = refreshResponse.body()
                val newJwt = body?.token.orEmpty()
                if (newJwt.isNotBlank()) {
                    val newRefresh = RefreshCookieExtractor.extractRefreshToken(refreshResponse)
                    authManager.onJwtRenewed(newJwt, newRefresh)
                    response.request.newBuilder()
                        .header("Authorization", "Bearer $newJwt")
                        .build()
                } else null
            } else {
                Log.w(TAG, "V2 refresh HTTP ${refreshResponse.code()}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "V2 token refresh failed", e)
            null
        }
    }

    private suspend fun tryLegacyRefresh(response: Response): Request? {
        return try {
            val renewResponse = apiServiceProvider.get().renewTokenLegacy()
            val newJwt = renewResponse.token
            if (newJwt.isNotBlank()) {
                authManager.onJwtRenewed(newJwt)
                response.request.newBuilder()
                    .header("Authorization", "Bearer $newJwt")
                    .build()
            } else null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Legacy JWT renewal failed", e)
            // Graceful server upgrade: if legacy fails, try v2 as fallback
            val v2Result = tryV2Refresh(response)
            if (v2Result != null) {
                Log.i(TAG, "Legacy refresh failed but v2 succeeded — upgrading server flag")
                authManager.storeServerIsV2(true)
            }
            v2Result
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

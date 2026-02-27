package com.rendyhd.vicu.data.remote.interceptor

import android.util.Log
import com.rendyhd.vicu.BuildConfig
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
        if (responseCount(response) >= MAX_RETRIES) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Max retries reached, giving up")
            authManager.setNeedsReAuth()
            return null
        }

        return runBlocking {
            withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                authManager.withRefreshLock {
                    // Check if another thread already refreshed the token
                    val currentToken = authManager.getBestTokenSync()
                    val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

                    if (currentToken != null && currentToken != failedToken) {
                        return@withRefreshLock response.request.newBuilder()
                            .header("Authorization", "Bearer $currentToken")
                            .build()
                    }

                    // Try refresh based on server version
                    if (authManager.isServerV2Cached) {
                        val v2Result = tryV2Refresh(response)
                        if (v2Result != null) return@withRefreshLock v2Result
                    } else {
                        val legacyResult = tryLegacyRefresh(response)
                        if (legacyResult != null) return@withRefreshLock legacyResult
                    }

                    // Fall back to API token
                    val apiToken = authManager.getBestToken()
                    if (apiToken != null && apiToken != failedToken) {
                        return@withRefreshLock response.request.newBuilder()
                            .header("Authorization", "Bearer $apiToken")
                            .build()
                    }

                    // All options exhausted
                    if (BuildConfig.DEBUG) Log.w(TAG, "All token options exhausted, needs re-auth")
                    authManager.setNeedsReAuth()
                    null
                }
            }
        }
    }

    private suspend fun tryV2Refresh(response: Response): Request? {
        val refreshToken = tokenStorage.getRefreshToken()
        if (refreshToken == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "No refresh token for v2 refresh")
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
            } else null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "V2 token refresh failed", e)
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
                if (BuildConfig.DEBUG) Log.i(TAG, "Legacy refresh failed but v2 succeeded — upgrading server flag")
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

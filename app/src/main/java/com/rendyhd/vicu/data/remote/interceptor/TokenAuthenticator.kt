package com.rendyhd.vicu.data.remote.interceptor

import android.util.Log
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import kotlinx.coroutines.runBlocking
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
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val MAX_RETRIES = 2
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= MAX_RETRIES) {
            Log.w(TAG, "Max retries reached, giving up")
            authManager.setNeedsReAuth()
            return null
        }

        return runBlocking {
            authManager.withRefreshLock {
                // Check if another thread already refreshed the token
                val currentToken = authManager.getBestTokenSync()
                val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

                if (currentToken != null && currentToken != failedToken) {
                    // Token was already refreshed by another thread
                    return@withRefreshLock response.request.newBuilder()
                        .header("Authorization", "Bearer $currentToken")
                        .build()
                }

                // Try to renew JWT
                try {
                    val renewResponse = apiServiceProvider.get().renewToken()
                    val newJwt = renewResponse.token
                    if (newJwt.isNotBlank()) {
                        authManager.onJwtRenewed(newJwt)
                        return@withRefreshLock response.request.newBuilder()
                            .header("Authorization", "Bearer $newJwt")
                            .build()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "JWT renewal failed", e)
                }

                // Fall back to API token
                val apiToken = authManager.getBestToken()
                if (apiToken != null && apiToken != failedToken) {
                    return@withRefreshLock response.request.newBuilder()
                        .header("Authorization", "Bearer $apiToken")
                        .build()
                }

                // All options exhausted
                Log.w(TAG, "All token options exhausted, needs re-auth")
                authManager.setNeedsReAuth()
                null
            }
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

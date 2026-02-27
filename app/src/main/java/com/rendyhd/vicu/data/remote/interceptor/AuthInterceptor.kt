package com.rendyhd.vicu.data.remote.interceptor

import android.util.Log
import com.rendyhd.vicu.auth.AuthManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val authManager: AuthManager,
) : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"
        private val SKIP_AUTH_PATHS = listOf("/login", "/info", "/auth/openid", "/user/token/refresh")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath

        if (SKIP_AUTH_PATHS.any { path.contains(it) }) {
            Log.d(TAG, "Skipping auth for path=$path")
            return chain.proceed(originalRequest)
        }

        val token = authManager.getBestTokenSync()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "NO TOKEN available for path=$path — request will be unauthenticated")
            return chain.proceed(originalRequest)
        }
        Log.d(TAG, "Attaching token (${token.take(10)}...) to path=$path")

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}

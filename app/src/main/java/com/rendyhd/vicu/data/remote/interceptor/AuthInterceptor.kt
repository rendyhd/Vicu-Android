package com.rendyhd.vicu.data.remote.interceptor

import android.util.Log
import com.rendyhd.vicu.BuildConfig
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
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping auth for path=$path")
            return chain.proceed(originalRequest)
        }

        val token = authManager.getBestTokenSync()
        if (token.isNullOrBlank()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "No token available for path=$path")
            return chain.proceed(originalRequest)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Attaching token to path=$path")

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}

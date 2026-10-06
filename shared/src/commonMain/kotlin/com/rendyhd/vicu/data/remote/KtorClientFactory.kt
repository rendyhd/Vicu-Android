package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.takeFrom
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object KtorClientFactory {
    private val SKIP_AUTH_PATHS = listOf("/login", "/info", "/auth/openid", "/user/token/refresh")

    fun create(
        engine: HttpClientEngine,
        json: Json,
        baseUrlHolder: BaseUrlHolder,
        authManager: AuthManager,
        // Request logging puts every URL (server host, search terms, filters) into logcat, so it
        // is opt-in and only the debug build turns it on (see KoinModules).
        enableLogging: Boolean = false
    ): HttpClient {
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }

            if (enableLogging) {
                install(Logging) {
                    logger = object : io.ktor.client.plugins.logging.Logger {
                        override fun log(message: String) {
                            com.rendyhd.vicu.util.Logger.d("KtorClient", message)
                        }
                    }
                    // Method, URL, and status only. Never log auth/cookie headers or bodies.
                    level = LogLevel.INFO
                }
            }
        }

        // 1. Dynamic Base URL Redirection Interceptor
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            var fullBaseUrl = baseUrlHolder.getFullBaseUrl()
            if (fullBaseUrl.isEmpty()) {
                baseUrlHolder.ensureInitialized()
                fullBaseUrl = baseUrlHolder.getFullBaseUrl()
            }

            if (fullBaseUrl.isNotEmpty()) {
                val originalPath = context.url.encodedPath.removePrefix("/")
                context.url.takeFrom(fullBaseUrl)
                if (originalPath.isNotEmpty()) {
                    val basePath = context.url.encodedPath
                    val separator = if (basePath.endsWith("/")) "" else "/"
                    context.url.encodedPath = "$basePath$separator$originalPath"
                }
            }
        }

        // 2. Authorization Header Injection Interceptor
        client.requestPipeline.intercept(HttpRequestPipeline.State) {
            val path = context.url.encodedPath
            if (SKIP_AUTH_PATHS.any { path.contains(it) }) {
                return@intercept
            }

            var token = authManager.getBestTokenSync()
            if (token.isNullOrBlank()) {
                token = authManager.ensureInitializedAndGetToken()
            }

            if (!token.isNullOrBlank()) {
                context.header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

        // 3. 401 Reactive Token Authenticator
        client.plugin(HttpSend).intercept { request ->
            val path = request.url.encodedPath
            var response = execute(request)

            if (response.response.status == HttpStatusCode.Unauthorized && !path.contains("/user/token/refresh")) {
                val refreshedRequest = authManager.withRefreshLock {
                    val currentToken = authManager.getBestTokenSync()
                    val failedToken = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

                    if (currentToken != null && currentToken != failedToken) {
                        request.headers[HttpHeaders.Authorization] = "Bearer $currentToken"
                        return@withRefreshLock request
                    }

                    val success = authManager.performV2Refresh()
                    if (success) {
                        val newToken = authManager.getBestTokenSync()
                        if (newToken != null) {
                            request.headers[HttpHeaders.Authorization] = "Bearer $newToken"
                            return@withRefreshLock request
                        }
                    }

                    // Fallback to API token
                    val apiToken = authManager.getBestToken()
                    if (apiToken != null && apiToken != failedToken) {
                        request.headers[HttpHeaders.Authorization] = "Bearer $apiToken"
                        return@withRefreshLock request
                    }

                    authManager.setNeedsReAuth()
                    null
                }

                if (refreshedRequest != null) {
                    response = execute(refreshedRequest)
                }
            }

            response
        }

        return client
    }
}

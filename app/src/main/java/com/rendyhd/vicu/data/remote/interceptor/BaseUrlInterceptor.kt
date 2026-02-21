package com.rendyhd.vicu.data.remote.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BaseUrlInterceptor @Inject constructor(
    private val baseUrlHolder: BaseUrlHolder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val fullBaseUrl = baseUrlHolder.getFullBaseUrl()

        if (fullBaseUrl.isEmpty()) {
            Log.w("BaseUrlInterceptor", "baseUrl is EMPTY — request going to localhost: ${originalRequest.url}")
            return chain.proceed(originalRequest)
        }

        val originalUrl = originalRequest.url
        val newUrl = originalUrl.newBuilder()
            .scheme(extractScheme(fullBaseUrl))
            .host(extractHost(fullBaseUrl))
            .port(extractPort(fullBaseUrl))
            .encodedPath(extractPath(fullBaseUrl) + originalUrl.encodedPath.removePrefix("/"))
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }

    private fun extractScheme(url: String): String {
        return if (url.startsWith("https")) "https" else "http"
    }

    private fun extractHost(url: String): String {
        val withoutScheme = url.substringAfter("://")
        val hostPort = withoutScheme.substringBefore("/")
        return hostPort.substringBefore(":")
    }

    private fun extractPort(url: String): Int {
        val withoutScheme = url.substringAfter("://")
        val hostPort = withoutScheme.substringBefore("/")
        return if (":" in hostPort) {
            hostPort.substringAfter(":").toIntOrNull() ?: defaultPort(url)
        } else {
            defaultPort(url)
        }
    }

    private fun extractPath(url: String): String {
        val withoutScheme = url.substringAfter("://")
        val pathStart = withoutScheme.indexOf("/")
        return if (pathStart >= 0) withoutScheme.substring(pathStart) else "/"
    }

    private fun defaultPort(url: String): Int {
        return if (url.startsWith("https")) 443 else 80
    }
}

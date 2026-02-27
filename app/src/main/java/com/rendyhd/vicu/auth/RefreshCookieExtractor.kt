package com.rendyhd.vicu.auth

import retrofit2.Response

object RefreshCookieExtractor {
    private const val COOKIE_NAME = "vikunja_refresh_token"

    fun extractRefreshToken(response: Response<*>): String? {
        val cookies = response.headers().values("Set-Cookie")
        for (cookie in cookies) {
            if (cookie.startsWith("$COOKIE_NAME=")) {
                val value = cookie.substringAfter("$COOKIE_NAME=").substringBefore(";")
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    fun buildCookieHeader(refreshToken: String): String {
        return "$COOKIE_NAME=$refreshToken"
    }
}

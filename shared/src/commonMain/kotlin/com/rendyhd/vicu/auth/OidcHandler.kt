package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.OidcCallbackDto
import com.rendyhd.vicu.data.remote.api.OidcProviderDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.api.VikunjaProblemDto
import kotlin.concurrent.Volatile

sealed class OidcResult {
    data class Success(val token: String, val refreshToken: String? = null) : OidcResult()
    data class NeedsTOTP(val message: String? = null) : OidcResult()
    data class Error(val message: String) : OidcResult()
}

class OidcHandler(
    private val apiServiceProvider: () -> VikunjaApiService,
) {
    companion object {
        private const val SCOPE = "openid email profile"
    }

    @Volatile
    private var pendingState: String? = null

    data class AuthParams(
        val authUrl: String,
        val redirectUri: String,
        val state: String,
    )

    fun prepareAuthParams(provider: OidcProviderDto, vikunjaUrl: String): AuthParams {
        val baseUrl = vikunjaUrl.trimEnd('/')
        val redirectUri = "$baseUrl/auth/openid/${provider.key}"
        val state = generateStateValue()
        pendingState = state

        val authUrl = "${provider.authUrl}" +
            "?client_id=${encodeUrl(provider.clientId)}" +
            "&redirect_uri=${encodeUrl(redirectUri)}" +
            "&response_type=code" +
            "&state=$state" +
            "&scope=${encodeUrl(SCOPE)}"

        return AuthParams(authUrl, redirectUri, state)
    }

    suspend fun handleCallbackResult(
        code: String?,
        returnedState: String?,
        error: String?,
        provider: OidcProviderDto,
        vikunjaUrl: String,
        totpPasscode: String? = null,
    ): OidcResult {
        return try {
            if (error != null) {
                pendingState = null
                return OidcResult.Error("OIDC error: $error")
            }

            if (code.isNullOrBlank()) {
                pendingState = null
                return OidcResult.Error("No authorization code received")
            }

            val expectedState = pendingState
            pendingState = null

            if (expectedState == null || returnedState != expectedState) {
                return OidcResult.Error("OIDC state mismatch — possible CSRF attack")
            }

            val baseUrl = vikunjaUrl.trimEnd('/')
            val redirectUri = "$baseUrl/auth/openid/${provider.key}"

            val callbackDto = OidcCallbackDto(
                code = code,
                redirectUrl = redirectUri,
                scope = SCOPE,
                totpPasscode = totpPasscode.orEmpty(),
            )

            val response = apiServiceProvider().exchangeOidcToken(provider.key, callbackDto)
            if (!response.isSuccessful) {
                oidcTotpChallenge(response.problem)?.let { return it }
                return OidcResult.Error(
                    response.problem?.detail
                        ?: "OIDC token exchange failed: HTTP ${response.code()}",
                )
            }
            val tokenResponse = response.body()
            if (tokenResponse == null || tokenResponse.token.isBlank()) {
                OidcResult.Error("Empty token received from server")
            } else {
                val refreshToken = RefreshCookieExtractor.extractRefreshToken(response)
                OidcResult.Success(tokenResponse.token, refreshToken)
            }
        } catch (e: Exception) {
            pendingState = null
            OidcResult.Error("OIDC callback failed: ${e.message}")
        }
    }
}

internal fun oidcTotpChallenge(problem: VikunjaProblemDto?): OidcResult.NeedsTOTP? {
    if (!isTotpProblemCode(problem?.code)) return null
    return OidcResult.NeedsTOTP(problem?.detail?.takeIf(String::isNotBlank))
}

expect fun encodeUrl(value: String): String
expect fun generateStateValue(): String

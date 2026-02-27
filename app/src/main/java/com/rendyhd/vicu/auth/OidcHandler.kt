package com.rendyhd.vicu.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rendyhd.vicu.data.remote.api.OidcCallbackDto
import com.rendyhd.vicu.data.remote.api.OidcProviderDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class OidcResult {
    data class Success(val token: String, val refreshToken: String? = null) : OidcResult()
    data class Error(val message: String) : OidcResult()
}

/**
 * Handles the OIDC login flow using Vikunja's own frontend callback URL
 * as redirect_uri — so no IdP configuration changes are needed.
 *
 * This mirrors the desktop Vicu app's approach (oidc-login.ts).
 */
@Singleton
class OidcHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: dagger.Lazy<VikunjaApiService>,
) {
    companion object {
        private const val SCOPE = "openid email profile"
    }

    /** Stores the OIDC state parameter for CSRF validation on callback. */
    @Volatile
    private var pendingState: String? = null

    fun buildAuthIntent(provider: OidcProviderDto, vikunjaUrl: String): Intent {
        val baseUrl = vikunjaUrl.trimEnd('/')
        val redirectUri = "$baseUrl/auth/openid/${provider.key}"
        val state = java.util.UUID.randomUUID().toString()
        pendingState = state

        val authUrl = "${provider.authUrl}" +
            "?client_id=${Uri.encode(provider.clientId)}" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&response_type=code" +
            "&state=$state" +
            "&scope=${Uri.encode(SCOPE)}"

        return Intent(context, OidcLoginActivity::class.java).apply {
            putExtra(OidcLoginActivity.EXTRA_AUTH_URL, authUrl)
            putExtra(OidcLoginActivity.EXTRA_REDIRECT_PREFIX, redirectUri)
            putExtra(OidcLoginActivity.EXTRA_EXPECTED_STATE, state)
        }
    }

    suspend fun handleCallback(
        intent: Intent,
        provider: OidcProviderDto,
        vikunjaUrl: String,
    ): OidcResult {
        return try {
            val error = intent.getStringExtra(OidcLoginActivity.EXTRA_ERROR)
            if (error != null) {
                pendingState = null
                return OidcResult.Error("OIDC error: $error")
            }

            val code = intent.getStringExtra(OidcLoginActivity.EXTRA_CODE)
            if (code.isNullOrBlank()) {
                pendingState = null
                return OidcResult.Error("No authorization code received")
            }

            // Validate state parameter to prevent CSRF
            val returnedState = intent.getStringExtra(OidcLoginActivity.EXTRA_STATE)
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
            )

            val response = apiService.get().exchangeOidcToken(provider.key, callbackDto)
            if (!response.isSuccessful) {
                return OidcResult.Error("OIDC token exchange failed: HTTP ${response.code()}")
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
            OidcResult.Error("OIDC callback failed: ${e.localizedMessage}")
        }
    }
}

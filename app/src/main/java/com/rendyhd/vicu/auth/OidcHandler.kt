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
    data class Success(val token: String) : OidcResult()
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

    fun buildAuthIntent(provider: OidcProviderDto, vikunjaUrl: String): Intent {
        val baseUrl = vikunjaUrl.trimEnd('/')
        val redirectUri = "$baseUrl/auth/openid/${provider.key}"
        val state = java.util.UUID.randomUUID().toString()

        val authUrl = "${provider.authUrl}" +
            "?client_id=${Uri.encode(provider.clientId)}" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&response_type=code" +
            "&state=$state" +
            "&scope=${Uri.encode(SCOPE)}"

        return Intent(context, OidcLoginActivity::class.java).apply {
            putExtra(OidcLoginActivity.EXTRA_AUTH_URL, authUrl)
            putExtra(OidcLoginActivity.EXTRA_REDIRECT_PREFIX, redirectUri)
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
                return OidcResult.Error("OIDC error: $error")
            }

            val code = intent.getStringExtra(OidcLoginActivity.EXTRA_CODE)
            if (code.isNullOrBlank()) {
                return OidcResult.Error("No authorization code received")
            }

            val baseUrl = vikunjaUrl.trimEnd('/')
            val redirectUri = "$baseUrl/auth/openid/${provider.key}"

            val callbackDto = OidcCallbackDto(
                code = code,
                redirectUrl = redirectUri,
                scope = SCOPE,
            )

            val tokenResponse = apiService.get().exchangeOidcToken(provider.key, callbackDto)
            if (tokenResponse.token.isBlank()) {
                OidcResult.Error("Empty token received from server")
            } else {
                OidcResult.Success(tokenResponse.token)
            }
        } catch (e: Exception) {
            OidcResult.Error("OIDC callback failed: ${e.localizedMessage}")
        }
    }
}

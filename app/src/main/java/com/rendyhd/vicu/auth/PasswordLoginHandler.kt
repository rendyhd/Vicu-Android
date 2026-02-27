package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.LoginRequestDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import javax.inject.Inject
import javax.inject.Singleton

sealed class PasswordLoginResult {
    data class Success(val token: String, val refreshToken: String? = null) : PasswordLoginResult()
    data object NeedsTOTP : PasswordLoginResult()
    data class Error(val message: String) : PasswordLoginResult()
}

@Singleton
class PasswordLoginHandler @Inject constructor(
    private val apiService: dagger.Lazy<VikunjaApiService>,
) {
    suspend fun login(username: String, password: String, totpPasscode: String? = null): PasswordLoginResult {
        return try {
            val request = LoginRequestDto(
                username = username,
                password = password,
                longToken = true,
                totpPasscode = totpPasscode ?: "",
            )
            val response = apiService.get().login(request)
            val code = response.code()
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    val token = body?.token.orEmpty()
                    if (token.isBlank()) {
                        PasswordLoginResult.Error("Empty token received")
                    } else {
                        val refreshToken = RefreshCookieExtractor.extractRefreshToken(response)
                        PasswordLoginResult.Success(token, refreshToken)
                    }
                }
                code == 412 -> PasswordLoginResult.NeedsTOTP
                code == 403 -> PasswordLoginResult.Error("Invalid username or password")
                else -> PasswordLoginResult.Error("Login failed: HTTP $code")
            }
        } catch (e: Exception) {
            PasswordLoginResult.Error("Connection error: ${e.localizedMessage}")
        }
    }
}

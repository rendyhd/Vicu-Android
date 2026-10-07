package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.LoginRequestDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService

sealed class PasswordLoginResult {
    data class Success(val token: String, val refreshToken: String? = null) : PasswordLoginResult()
    data object NeedsTOTP : PasswordLoginResult()
    data class Error(val message: String) : PasswordLoginResult()
}

class PasswordLoginHandler(
    private val apiServiceProvider: () -> VikunjaApiService,
) {
    suspend fun login(username: String, password: String, totpPasscode: String? = null): PasswordLoginResult {
        return try {
            val request = LoginRequestDto(
                username = username,
                password = password,
                longToken = true,
                totpPasscode = totpPasscode ?: "",
            )
            val response = apiServiceProvider().login(request)
            val status = response.code()
            val problemCode = response.problem?.code
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
                isTotpProblemCode(problemCode) && totpPasscode.isNullOrBlank() ->
                    PasswordLoginResult.NeedsTOTP
                problemCode == ERROR_INVALID_CREDENTIALS ->
                    PasswordLoginResult.Error(response.problem.detail.ifBlank { "Invalid username or password" })
                else ->
                    PasswordLoginResult.Error(
                        response.problem?.detail?.takeIf { it.isNotBlank() } ?: "Login failed: HTTP $status",
                    )
            }
        } catch (e: Exception) {
            PasswordLoginResult.Error("Connection error: ${e.message}")
        }
    }

    private companion object {
        const val ERROR_INVALID_CREDENTIALS = 1011L
    }
}

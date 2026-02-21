package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
    @SerialName("long_token") val longToken: Boolean = true,
    @SerialName("totp_passcode") val totpPasscode: String = "",
)

@Serializable
data class TokenResponseDto(
    val token: String = "",
)

@Serializable
data class OidcCallbackDto(
    val code: String = "",
    @SerialName("redirect_url") val redirectUrl: String = "",
    val scope: String = "",
)

@Serializable
data class OidcProviderDto(
    val key: String = "",
    val name: String = "",
    @SerialName("auth_url") val authUrl: String = "",
    @SerialName("logout_url") val logoutUrl: String = "",
    @SerialName("client_id") val clientId: String = "",
)

@Serializable
data class ServerInfoDto(
    val auth: AuthInfoDto = AuthInfoDto(),
    val version: String = "",
)

@Serializable
data class AuthInfoDto(
    val local: LocalAuthInfoDto = LocalAuthInfoDto(),
    @SerialName("openid_connect") val openidConnect: OpenIdAuthInfoDto = OpenIdAuthInfoDto(),
)

@Serializable
data class LocalAuthInfoDto(
    val enabled: Boolean = true,
)

@Serializable
data class OpenIdAuthInfoDto(
    val enabled: Boolean = false,
    val providers: List<OidcProviderDto> = emptyList(),
)

@Serializable
data class LabelTaskDto(
    @SerialName("label_id") val labelId: Long,
)

@Serializable
data class TaskPositionDto(
    val position: Double,
)

@Serializable
data class CreateRelationDto(
    @SerialName("other_task_id") val otherTaskId: Long,
    @SerialName("relation_kind") val relationKind: String,
)

@Serializable
data class ApiTokenRequestDto(
    val title: String = "Vicu Android",
    @SerialName("expires_at") val expiresAt: String = "2099-12-31T23:59:59Z",
)

@Serializable
data class ApiTokenResponseDto(
    val id: Long = 0,
    val token: String = "",
)

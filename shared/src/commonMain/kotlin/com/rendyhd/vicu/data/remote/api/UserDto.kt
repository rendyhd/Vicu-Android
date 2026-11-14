package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: Long = 0,
    val username: String = "",
    val name: String = "",
    val email: String = "",
    val created: String = "",
    val updated: String = "",
    /** Present on `GET user` only: the account's own settings. Vicu reads, never writes, them. */
    val settings: UserSettingsDto? = null,
)

@Serializable
data class UserSettingsDto(
    /** Vikunja's default project: setup's suggestion for the Inbox when no device chose one. */
    @SerialName("default_project_id") val defaultProjectId: Long = 0,
    /**
     * Vikunja's own week start, 0 Sunday to 6 Saturday. The "Week starts on" setting offers it; it
     * is never followed by itself, because an account that never set it reads 0 (contract 2.1).
     */
    @SerialName("week_start") val weekStart: Int? = null,
)

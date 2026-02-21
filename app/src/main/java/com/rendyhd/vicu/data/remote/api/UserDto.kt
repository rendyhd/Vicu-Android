package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: Long = 0,
    val username: String = "",
    val name: String = "",
    val email: String = "",
    val created: String = "",
    val updated: String = "",
)

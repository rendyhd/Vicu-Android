package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Long,
    val username: String,
    val name: String,
)

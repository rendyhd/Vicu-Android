package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LabelDto(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    @SerialName("hex_color") val hexColor: String = "",
    @SerialName("created_by") val createdBy: UserDto? = null,
    val created: String = "",
    val updated: String = "",
)

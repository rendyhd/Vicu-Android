package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LabelDto(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    @SerialName("hex_color") val hexColor: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("created_by") val createdBy: UserDto? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val created: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val updated: String = "",
)

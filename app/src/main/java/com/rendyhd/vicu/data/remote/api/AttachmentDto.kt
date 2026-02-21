package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttachmentDto(
    val id: Long = 0,
    @SerialName("task_id") val taskId: Long = 0,
    val file: FileDto? = null,
    val created: String = "",
    @SerialName("created_by") val createdBy: UserDto? = null,
)

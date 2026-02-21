package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskRelationDto(
    @SerialName("task_id") val taskId: Long = 0,
    @SerialName("other_task_id") val otherTaskId: Long = 0,
    @SerialName("relation_kind") val relationKind: String = "",
    val created: String = "",
    @SerialName("created_by") val createdBy: UserDto? = null,
)

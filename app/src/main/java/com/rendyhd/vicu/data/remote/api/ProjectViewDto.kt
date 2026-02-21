package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectViewDto(
    val id: Long = 0,
    @SerialName("project_id") val projectId: Long = 0,
    val title: String = "",
    @SerialName("view_kind") val viewKind: String = "",
    val position: Double = 0.0,
)

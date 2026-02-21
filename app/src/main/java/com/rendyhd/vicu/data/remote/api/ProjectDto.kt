package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectDto(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    @SerialName("hex_color") val hexColor: String = "",
    @SerialName("parent_project_id") val parentProjectId: Long = 0,
    val position: Double = 0.0,
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    val identifier: String = "",
    val owner: UserDto? = null,
    val created: String = "",
    val updated: String = "",
)

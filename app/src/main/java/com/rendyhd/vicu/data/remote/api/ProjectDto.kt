package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val owner: UserDto? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val created: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val updated: String = "",
)

/** Minimal payload for PUT /projects — matches desktop CreateProjectPayload */
@Serializable
data class CreateProjectDto(
    val title: String,
    val description: String = "",
    @SerialName("hex_color") val hexColor: String = "",
    @SerialName("parent_project_id") val parentProjectId: Long = 0,
)

/** Minimal payload for POST /projects/{id} — matches desktop UpdateProjectPayload */
@Serializable
data class UpdateProjectDto(
    val title: String = "",
    val description: String = "",
    @SerialName("hex_color") val hexColor: String = "",
    @SerialName("is_archived") val isArchived: Boolean = false,
    val position: Double = 0.0,
    @SerialName("parent_project_id") val parentProjectId: Long = 0,
)

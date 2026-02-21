package com.rendyhd.vicu.data.mapper

import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.remote.api.ProjectDto
import com.rendyhd.vicu.data.remote.api.UserDto
import com.rendyhd.vicu.domain.model.Project
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectMapper @Inject constructor() {

    fun ProjectDto.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
        parentProjectId = parentProjectId,
        position = position,
        isArchived = isArchived,
        isFavorite = isFavorite,
        identifier = identifier,
        created = created,
        updated = updated,
        ownerId = owner?.id ?: 0,
    )

    fun ProjectEntity.toDomain(): Project = Project(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
        parentProjectId = parentProjectId,
        position = position,
        isArchived = isArchived,
        isFavorite = isFavorite,
        identifier = identifier,
        created = created,
        updated = updated,
        ownerId = ownerId,
    )

    fun Project.toDto(): ProjectDto = ProjectDto(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
        parentProjectId = parentProjectId,
        position = position,
        isArchived = isArchived,
        isFavorite = isFavorite,
        identifier = identifier,
        owner = UserDto(id = ownerId),
        created = created,
        updated = updated,
    )
}

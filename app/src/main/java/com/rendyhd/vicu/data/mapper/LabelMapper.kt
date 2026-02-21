package com.rendyhd.vicu.data.mapper

import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.remote.api.LabelDto
import com.rendyhd.vicu.domain.model.Label
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LabelMapper @Inject constructor() {

    fun LabelDto.toEntity(): LabelEntity = LabelEntity(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
        createdById = createdBy?.id ?: 0,
        created = created,
        updated = updated,
    )

    fun LabelEntity.toDomain(): Label = Label(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
        createdById = createdById,
        created = created,
        updated = updated,
    )

    fun LabelDto.toDomain(): Label = Label(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
        createdById = createdBy?.id ?: 0,
        created = created,
        updated = updated,
    )

    fun Label.toDto(): LabelDto = LabelDto(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
    )

    fun Label.toEntity(): LabelEntity = LabelEntity(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
        createdById = createdById,
        created = created,
        updated = updated,
    )
}

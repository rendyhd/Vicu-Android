package com.rendyhd.vicu.data.mapper

import com.rendyhd.vicu.data.local.entity.AttachmentEntity
import com.rendyhd.vicu.data.remote.api.AttachmentDto
import com.rendyhd.vicu.domain.model.Attachment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentMapper @Inject constructor() {

    fun AttachmentDto.toEntity(taskId: Long): AttachmentEntity = AttachmentEntity(
        id = id,
        taskId = taskId,
        fileName = file?.name ?: "",
        mimeType = file?.mime ?: "",
        fileSize = file?.size ?: 0,
        createdAt = created,
    )

    fun AttachmentEntity.toDomain(): Attachment = Attachment(
        id = id,
        taskId = taskId,
        fileName = fileName,
        mimeType = mimeType,
        fileSize = fileSize,
        createdAt = createdAt,
    )
}

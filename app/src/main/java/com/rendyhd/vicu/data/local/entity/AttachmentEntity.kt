package com.rendyhd.vicu.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    indices = [Index(value = ["taskId"])]
)
data class AttachmentEntity(
    @PrimaryKey val id: Long,
    val taskId: Long = 0,
    val fileName: String = "",
    val mimeType: String = "",
    val fileSize: Long = 0,
    val createdAt: String = "",
)

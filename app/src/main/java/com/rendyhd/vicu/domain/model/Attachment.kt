package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    val id: Long,
    val taskId: Long,
    val fileName: String = "",
    val mimeType: String = "",
    val fileSize: Long = 0,
    val createdAt: String = "",
)

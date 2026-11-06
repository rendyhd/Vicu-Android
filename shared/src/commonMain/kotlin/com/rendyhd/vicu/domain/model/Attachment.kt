package com.rendyhd.vicu.domain.model

import com.rendyhd.vicu.util.Collaboration
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

private val VIEWABLE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

/**
 * Whether the in-app viewer shows this file: the image types every platform can draw. Another image
 * type (HEIC, TIFF) opens in another app, whatever the name says; without an image type from the
 * server the extension decides.
 */
val Attachment.isViewableImage: Boolean
    get() = when {
        Collaboration.isInlineImageType(mimeType) -> true
        mimeType.trim().startsWith("image/", ignoreCase = true) -> false
        else -> fileName.substringAfterLast('.', "").trim().lowercase() in VIEWABLE_IMAGE_EXTENSIONS
    }

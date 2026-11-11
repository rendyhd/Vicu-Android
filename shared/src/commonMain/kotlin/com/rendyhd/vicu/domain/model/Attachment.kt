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
 * A file attached while the server could not be reached: kept on the device and queued, then
 * uploaded when the server answers again. [failed] when the server refused it; [error] says why.
 */
data class WaitingUpload(
    /** The queued action, for Retry and Remove. */
    val id: Long,
    val taskId: Long,
    val fileName: String,
    val fileSize: Long,
    /** Goes to the end of the notes once uploaded. */
    val intoNotes: Boolean,
    /** The copy on the device, for the thumbnail; null when it is gone. */
    val localPath: String?,
    val failed: Boolean = false,
    val error: String? = null,
    /** False when sending it again cannot help (larger than the server accepts). */
    val canRetry: Boolean = true,
) {
    /** An image the app can draw from the copy (by its name: the picker gives no type). */
    val isImage: Boolean
        get() = fileName.substringAfterLast('.', "").trim().lowercase() in VIEWABLE_IMAGE_EXTENSIONS
}

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

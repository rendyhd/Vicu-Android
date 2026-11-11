package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A file attached while the server could not be reached, waiting in the queue: entity type
 * [ENTITY_TYPE], action [UPLOAD], `entityId` the task (a temporary id while the task's own create
 * waits). The bytes are in [com.rendyhd.vicu.data.local.OfflineAttachmentFiles] under [file].
 */
@Serializable
data class QueuedUpload(
    /** The stored file's name in the offline attachments folder, never a path. */
    val file: String,
    /** The name shown and sent with the upload. */
    val name: String,
    val size: Long,
    /** Put `[[image:N]]` at the end of the notes once uploaded, as an image added online is. */
    val intoNotes: Boolean = false,
    /** The upload went through; a retry only does what is left (the notes). */
    val uploaded: Boolean = false,
    /** The attachment the upload made, once known. */
    val attachmentId: Long? = null,
    /** Why the server refused it, shown on the failed row. */
    val error: String? = null,
    /** Refused as larger than the server accepts: retrying cannot help. */
    val tooLarge: Boolean = false,
)

object AttachmentQueue {
    const val ENTITY_TYPE = "attachment"
    const val UPLOAD = "upload"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(upload: QueuedUpload): String = json.encodeToString(QueuedUpload.serializer(), upload)

    /** The upload of [action], or null when it is not one or cannot be read. */
    fun decode(action: PendingActionEntity): QueuedUpload? {
        if (action.entityType != ENTITY_TYPE || action.actionType != UPLOAD) return null
        return runCatching { json.decodeFromString(QueuedUpload.serializer(), action.payload) }.getOrNull()
    }
}

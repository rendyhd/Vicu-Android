package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.WaitingUpload

/** What the app says about files waiting to upload; the desktop uses the same words. */
object WaitingUploadText {
    /** The snackbar after a file was kept for later. */
    fun queued(fileName: String): String = "$fileName uploads when you're back online"

    /** Quick add's confirmation adds this when files were kept for later. */
    fun queuedCount(count: Int): String =
        if (count == 1) "1 file uploads when you're back online." else "$count files upload when you're back online."

    /** The line under the file name in the task's attachments. */
    fun status(upload: WaitingUpload): String = when {
        upload.failed -> "Not uploaded: ${upload.error ?: "the server refused it"}"
        upload.intoNotes -> "Waiting to upload · goes into the notes"
        else -> "Waiting to upload · ${formatByteSize(upload.fileSize)}"
    }

    /** What a screen reader says for the row. */
    fun description(upload: WaitingUpload): String =
        if (upload.failed) "${upload.fileName}, not uploaded" else "${upload.fileName}, waiting to upload"
}

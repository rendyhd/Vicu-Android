package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.WaitingUpload
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

/** What adding a picked file to a task did. */
sealed interface AttachResult {
    data class Uploaded(val attachment: Attachment) : AttachResult

    /** The server could not be reached: the file is kept on the device and uploads later. */
    data class Queued(val fileName: String) : AttachResult

    /** The file cannot be attached (too large, unreadable, refused by the server). */
    data class Refused(val message: String) : AttachResult
}

/** A waiting upload the user removed, kept for the snackbar's Undo ([AttachmentRepository.restoreWaiting]). */
class RemovedUpload(val taskId: Long, val fileName: String, internal val payload: String, internal val createdAt: String)

interface AttachmentRepository {
    fun getByTaskId(taskId: Long): Flow<List<Attachment>>

    /**
     * The largest file the server accepts: `max_file_size` from its `/info`, or 20 MB when it
     * does not say or cannot be asked.
     */
    suspend fun maxUploadBytes(): Long

    /**
     * Streams the file behind [uriString] (a picked content URI) to the task, without reading it
     * into memory. A file over [maxUploadBytes] is refused before anything is sent. Needs the
     * server: a comment names the file by its address, which only exists once it is uploaded.
     */
    suspend fun uploadPicked(taskId: Long, uriString: String): NetworkResult<Attachment>

    /**
     * Adds the file behind [uriString] to the task: uploaded at once when the server answers,
     * otherwise copied onto the device and queued (also for a task created offline, which has no
     * server id yet). [intoNotes] puts the image at the end of the notes once it is uploaded; an
     * upload that went through at once leaves that to the caller, as before.
     */
    suspend fun attachPicked(taskId: Long, uriString: String, intoNotes: Boolean = false): AttachResult

    /** True when a file added to [taskId] now would wait for the server (offline, or a task created offline). */
    fun waitsForServer(taskId: Long): Boolean

    /** The task's files waiting to upload or refused by the server, oldest first. */
    fun waitingUploads(taskId: Long): Flow<List<WaitingUpload>>

    /**
     * Cancels a waiting or failed upload. Its copy stays until a later sync clears what nothing
     * names, so [restoreWaiting] can bring it back. Null when it was no longer waiting.
     */
    suspend fun removeWaiting(id: Long): RemovedUpload?

    /** Queues [removed] again; false when its copy is gone. */
    suspend fun restoreWaiting(removed: RemovedUpload): Boolean

    /** Sends a failed upload again. */
    suspend fun retryWaiting(id: Long)

    /** Streams [attachment] into the app cache as it arrives and returns the cached file's path. */
    suspend fun downloadToCache(attachment: Attachment): NetworkResult<String>

    /**
     * Deletes the attachment on the server, then in the local cache. When the server refuses (or
     * cannot be reached) nothing local changes, so the caller has nothing to roll back.
     */
    suspend fun delete(taskId: Long, attachmentId: Long): NetworkResult<Unit>

    suspend fun refreshForTask(taskId: Long): NetworkResult<Unit>
}

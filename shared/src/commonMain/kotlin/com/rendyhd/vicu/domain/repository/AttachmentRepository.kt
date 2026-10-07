package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

interface AttachmentRepository {
    fun getByTaskId(taskId: Long): Flow<List<Attachment>>

    /**
     * The largest file the server accepts: `max_file_size` from its `/info`, or 20 MB when it
     * does not say or cannot be asked.
     */
    suspend fun maxUploadBytes(): Long

    /**
     * Streams the file behind [uriString] (a picked content URI) to the task, without reading it
     * into memory. A file over [maxUploadBytes] is refused before anything is sent.
     */
    suspend fun uploadPicked(taskId: Long, uriString: String): NetworkResult<Attachment>

    /** Streams [attachment] into the app cache as it arrives and returns the cached file's path. */
    suspend fun downloadToCache(attachment: Attachment): NetworkResult<String>

    /**
     * Deletes the attachment on the server, then in the local cache. When the server refuses (or
     * cannot be reached) nothing local changes, so the caller has nothing to roll back.
     */
    suspend fun delete(taskId: Long, attachmentId: Long): NetworkResult<Unit>

    suspend fun refreshForTask(taskId: Long): NetworkResult<Unit>
}

package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.OfflineAttachmentFiles
import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.local.dao.AttachmentQueue
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.QueuedUpload
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.AttachmentMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.WaitingUpload
import com.rendyhd.vicu.domain.repository.AttachResult
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.RemovedUpload
import com.rendyhd.vicu.util.DEFAULT_MAX_UPLOAD_BYTES
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.UploadOpen
import com.rendyhd.vicu.util.UploadSource
import com.rendyhd.vicu.util.formatByteSize
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.parseByteSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class AttachmentRepositoryImpl(
    private val attachmentDao: AttachmentDao,
    private val api: VikunjaApiService,
    private val attachmentMapper: AttachmentMapper,
    private val platformFiles: PlatformFiles,
    /** Where Room rows are mapped to domain models: off the main thread; tests pass an unconfined one. */
    private val mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The change queue; without it (and [offlineFiles]) nothing can wait for the server. */
    private val pendingActionDao: PendingActionDao? = null,
    /** Copies of files attached while the server could not be reached. */
    private val offlineFiles: OfflineAttachmentFiles? = null,
    /** Offline means queue at once instead of waiting for a request to fail. */
    private val networkMonitor: NetworkMonitor? = null,
    /** Starts a sync once an upload is queued. */
    private val platformHooks: PlatformRepositoryHooks? = null,
) : AttachmentRepository {

    private val limitMutex = Mutex()
    private var cachedLimit: Long? = null
    private var cachedLimitAtMs = 0L

    override fun getByTaskId(taskId: Long): Flow<List<Attachment>> =
        attachmentDao.getByTaskId(taskId).map { entities ->
            entities.map { with(attachmentMapper) { it.toDomain() } }
        }.flowOn(mappingDispatcher)

    override suspend fun maxUploadBytes(): Long = limitMutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        cachedLimit?.takeIf { now - cachedLimitAtMs < LIMIT_TTL_MS }?.let { return@withLock it }
        // Offline: the last limit the server gave, without waiting for a request to fail.
        if (networkMonitor?.isOnline?.value == false) return@withLock cachedLimit ?: DEFAULT_MAX_UPLOAD_BYTES
        try {
            val limit = parseByteSize(api.getServerInfo().maxFileSize) ?: DEFAULT_MAX_UPLOAD_BYTES
            cachedLimit = limit
            cachedLimitAtMs = now
            limit
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or an old server: fall back for this upload and ask again next time.
            cachedLimit ?: DEFAULT_MAX_UPLOAD_BYTES
        }
    }

    /** Opens the picked file, or says why it cannot be attached. */
    private suspend fun open(uriString: String, limit: Long): Pair<UploadSource?, String?> {
        val opened = try {
            platformFiles.openForUpload(uriString, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UploadOpen.Unreadable
        }
        return when (opened) {
            is UploadOpen.Ready ->
                if (opened.source.size > limit) {
                    opened.source.close()
                    null to tooLargeMessage(opened.source.fileName, opened.source.size, limit)
                } else {
                    opened.source to null
                }
            is UploadOpen.TooLarge -> null to tooLargeMessage(opened.fileName, opened.size, limit)
            UploadOpen.Unreadable -> null to "Could not read the file"
        }
    }

    override suspend fun uploadPicked(taskId: Long, uriString: String): NetworkResult<Attachment> {
        val limit = maxUploadBytes()
        val (source, problem) = open(uriString, limit)
        if (source == null) return NetworkResult.Error(problem ?: "Could not read the file")
        return try {
            NetworkResult.Success(upload(taskId, source))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to upload attachment")
        } finally {
            source.close()
        }
    }

    /** Uploads [source], stores the task's new list and returns the attachment it made. */
    private suspend fun upload(taskId: Long, source: UploadSource): Attachment {
        val beforeIds = api.getAttachments(taskId).map { it.id }.toSet()
        api.uploadAttachment(taskId, source.fileName, source.size, source::channel)
        val afterDtos = api.getAttachments(taskId)
        val newDto = afterDtos.filter { it.id !in beforeIds }.maxByOrNull { it.id }
            ?: throw IllegalStateException("Upload succeeded but new attachment not found")
        attachmentDao.replaceForTask(taskId, afterDtos.map { with(attachmentMapper) { it.toEntity(taskId) } })
        val newEntity = with(attachmentMapper) { newDto.toEntity(taskId) }
        return with(attachmentMapper) { newEntity.toDomain() }
    }

    override suspend fun attachPicked(taskId: Long, uriString: String, intoNotes: Boolean): AttachResult {
        val limit = maxUploadBytes()
        val (source, problem) = open(uriString, limit)
        if (source == null) return AttachResult.Refused(problem ?: "Could not read the file")
        try {
            // A task created offline has no server id yet; offline, a request would only fail.
            if (!waitsForServer(taskId)) {
                try {
                    return AttachResult.Uploaded(upload(taskId, source))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isRetriableNetworkError(e)) {
                        return AttachResult.Refused(e.message ?: "Failed to upload attachment")
                    }
                    Logger.w(TAG, "Upload to task $taskId failed (${e.message}); keeping the file for later")
                }
            }
            return queue(taskId, source, intoNotes)
        } finally {
            source.close()
        }
    }

    private suspend fun queue(taskId: Long, source: UploadSource, intoNotes: Boolean): AttachResult {
        val dao = pendingActionDao
        val files = offlineFiles
        if (dao == null || files == null) return AttachResult.Refused("Attaching files needs a connection")
        val stored = try {
            files.save(source.channel())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Could not keep ${source.fileName} on the device: ${e.message}", e)
            return AttachResult.Refused("Could not keep \"${source.fileName}\" on this device")
        }
        val now = DateUtils.nowIso()
        dao.insert(
            PendingActionEntity(
                entityType = AttachmentQueue.ENTITY_TYPE,
                entityId = taskId,
                actionType = AttachmentQueue.UPLOAD,
                payload = AttachmentQueue.encode(
                    QueuedUpload(file = stored, name = source.fileName, size = source.size, intoNotes = intoNotes),
                ),
                createdAt = now,
                updatedAt = now,
            ),
        )
        platformHooks?.triggerSync()
        return AttachResult.Queued(source.fileName)
    }

    override fun waitsForServer(taskId: Long): Boolean = taskId < 0 || networkMonitor?.isOnline?.value == false

    override fun waitingUploads(taskId: Long): Flow<List<WaitingUpload>> {
        val dao = pendingActionDao ?: return flowOf(emptyList())
        return dao.observeActiveForTask(AttachmentQueue.ENTITY_TYPE, taskId).map { actions ->
            actions.mapNotNull { action ->
                val upload = AttachmentQueue.decode(action) ?: return@mapNotNull null
                // Uploaded, only the notes left: the attachment list already shows it.
                if (upload.uploaded) return@mapNotNull null
                WaitingUpload(
                    id = action.id,
                    taskId = action.entityId,
                    fileName = upload.name,
                    fileSize = upload.size,
                    intoNotes = upload.intoNotes,
                    localPath = offlineFiles?.let { files -> runCatching { files.pathOf(upload.file) }.getOrNull() },
                    failed = action.status == "failed",
                    error = upload.error,
                    canRetry = !upload.tooLarge,
                )
            }
        }.flowOn(mappingDispatcher)
    }

    override suspend fun removeWaiting(id: Long): RemovedUpload? {
        val dao = pendingActionDao ?: return null
        val action = dao.getById(id) ?: return null
        val upload = AttachmentQueue.decode(action) ?: return null
        if (action.status == "processing") return null
        dao.deleteById(id)
        return RemovedUpload(action.entityId, upload.name, action.payload, action.createdAt)
    }

    override suspend fun restoreWaiting(removed: RemovedUpload): Boolean {
        val dao = pendingActionDao ?: return false
        val files = offlineFiles ?: return false
        val upload = runCatching { AttachmentQueue.decode(PendingActionEntity(entityType = AttachmentQueue.ENTITY_TYPE, actionType = AttachmentQueue.UPLOAD, payload = removed.payload)) }.getOrNull()
            ?: return false
        if (!files.exists(upload.file)) return false
        dao.insert(
            PendingActionEntity(
                entityType = AttachmentQueue.ENTITY_TYPE,
                entityId = removed.taskId,
                actionType = AttachmentQueue.UPLOAD,
                payload = AttachmentQueue.encode(upload.copy(error = null)),
                createdAt = removed.createdAt,
                updatedAt = DateUtils.nowIso(),
            ),
        )
        platformHooks?.triggerSync()
        return true
    }

    override suspend fun retryWaiting(id: Long) {
        val dao = pendingActionDao ?: return
        val action = dao.getById(id) ?: return
        val upload = AttachmentQueue.decode(action) ?: return
        dao.updatePayload(id, AttachmentQueue.encode(upload.copy(error = null)))
        if (dao.retryFailed(id) > 0) platformHooks?.triggerSync()
    }

    override suspend fun downloadToCache(attachment: Attachment): NetworkResult<String> {
        return try {
            val path = api.downloadAttachment(attachment.taskId, attachment.id) { channel ->
                platformFiles.saveToCache(attachment.id, attachment.fileName.ifBlank { "attachment" }, channel)
            }
            NetworkResult.Success(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to download attachment")
        }
    }

    override suspend fun delete(taskId: Long, attachmentId: Long): NetworkResult<Unit> {
        return try {
            try {
                api.deleteAttachment(taskId, attachmentId)
            } catch (e: VikunjaApiException) {
                // Already gone on the server: the local row should go too.
                if (e.httpStatus != 404) throw e
            }
            attachmentDao.deleteById(attachmentId)
            NetworkResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to delete attachment")
        }
    }

    override suspend fun refreshForTask(taskId: Long): NetworkResult<Unit> {
        return try {
            val dtos = api.getAttachments(taskId)
            val entities = dtos.map { with(attachmentMapper) { it.toEntity(taskId) } }
            // The server's list is the whole list: an attachment deleted on another device goes.
            // Files still waiting to upload live in the queue, not here, so nothing local is lost.
            attachmentDao.replaceForTask(taskId, entities)
            NetworkResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to refresh attachments")
        }
    }

    private fun tooLargeMessage(fileName: String, size: Long, limit: Long) =
        "\"$fileName\" is ${formatByteSize(size)}, but the server accepts files up to ${formatByteSize(limit)}"

    private companion object {
        const val TAG = "Attachments"

        /** How long the server's limit is trusted before `/info` is asked again. */
        const val LIMIT_TTL_MS = 10 * 60 * 1_000L
    }
}

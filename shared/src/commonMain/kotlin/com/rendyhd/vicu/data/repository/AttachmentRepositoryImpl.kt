package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.mapper.AttachmentMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.util.DEFAULT_MAX_UPLOAD_BYTES
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.UploadOpen
import com.rendyhd.vicu.util.formatByteSize
import com.rendyhd.vicu.util.parseByteSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class AttachmentRepositoryImpl(
    private val attachmentDao: AttachmentDao,
    private val api: VikunjaApiService,
    private val attachmentMapper: AttachmentMapper,
    private val platformFiles: PlatformFiles,
    /** Where Room rows are mapped to domain models: off the main thread; tests pass an unconfined one. */
    private val mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
        try {
            val limit = parseByteSize(api.getServerInfo().maxFileSize) ?: DEFAULT_MAX_UPLOAD_BYTES
            cachedLimit = limit
            cachedLimitAtMs = now
            limit
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or an old server: fall back for this upload and ask again next time.
            DEFAULT_MAX_UPLOAD_BYTES
        }
    }

    override suspend fun uploadPicked(taskId: Long, uriString: String): NetworkResult<Attachment> {
        val limit = maxUploadBytes()
        val opened = try {
            platformFiles.openForUpload(uriString, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UploadOpen.Unreadable
        }
        val source = when (opened) {
            is UploadOpen.Ready -> opened.source
            is UploadOpen.TooLarge -> return NetworkResult.Error(tooLargeMessage(opened.fileName, opened.size, limit))
            UploadOpen.Unreadable -> return NetworkResult.Error("Could not read the file")
        }
        return try {
            if (source.size > limit) {
                return NetworkResult.Error(tooLargeMessage(source.fileName, source.size, limit))
            }
            val beforeIds = api.getAttachments(taskId).map { it.id }.toSet()
            api.uploadAttachment(taskId, source.fileName, source.size, source::channel)
            val afterDtos = api.getAttachments(taskId)
            val newDto = afterDtos.filter { it.id !in beforeIds }.maxByOrNull { it.id }
                ?: return NetworkResult.Error("Upload succeeded but new attachment not found")

            val afterEntities = afterDtos.map { with(attachmentMapper) { it.toEntity(taskId) } }
            attachmentDao.replaceForTask(taskId, afterEntities)

            val newEntity = with(attachmentMapper) { newDto.toEntity(taskId) }
            NetworkResult.Success(with(attachmentMapper) { newEntity.toDomain() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to upload attachment")
        } finally {
            source.close()
        }
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
            // Uploads are never kept only here (they go straight to the server), so nothing local is lost.
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
        /** How long the server's limit is trusted before `/info` is asked again. */
        const val LIMIT_TTL_MS = 10 * 60 * 1_000L
    }
}

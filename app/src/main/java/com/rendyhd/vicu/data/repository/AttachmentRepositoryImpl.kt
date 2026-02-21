package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.mapper.AttachmentMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentRepositoryImpl @Inject constructor(
    private val attachmentDao: AttachmentDao,
    private val api: VikunjaApiService,
    private val attachmentMapper: AttachmentMapper,
) : AttachmentRepository {

    override fun getByTaskId(taskId: Long): Flow<List<Attachment>> =
        attachmentDao.getByTaskId(taskId).map { entities ->
            entities.map { with(attachmentMapper) { it.toDomain() } }
        }

    override suspend fun upload(taskId: Long, filePart: MultipartBody.Part): NetworkResult<Attachment> {
        return try {
            val responseDto = api.uploadAttachment(taskId, filePart)
            val entity = with(attachmentMapper) { responseDto.toEntity(taskId) }
            attachmentDao.upsert(entity)
            NetworkResult.Success(with(attachmentMapper) { entity.toDomain() })
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to upload attachment")
        }
    }

    override suspend fun download(taskId: Long, attachmentId: Long): NetworkResult<ResponseBody> {
        return try {
            val body = api.downloadAttachment(taskId, attachmentId)
            NetworkResult.Success(body)
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to download attachment")
        }
    }

    override suspend fun delete(taskId: Long, attachmentId: Long): NetworkResult<Unit> {
        return try {
            attachmentDao.deleteById(attachmentId)
            api.deleteAttachment(taskId, attachmentId)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to delete attachment")
        }
    }

    override suspend fun refreshForTask(taskId: Long): NetworkResult<Unit> {
        return try {
            val dtos = api.getAttachments(taskId)
            val entities = dtos.map { with(attachmentMapper) { it.toEntity(taskId) } }
            attachmentDao.upsertAll(entities)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to refresh attachments")
        }
    }
}

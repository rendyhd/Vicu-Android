package com.rendyhd.vicu.worker

import com.rendyhd.vicu.data.local.OfflineAttachmentFiles
import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.local.dao.AttachmentQueue
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.QueuedUpload
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.AttachmentMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.ImageTokens
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.formatByteSize
import com.rendyhd.vicu.util.isRetriableNetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sends the files attached while the server could not be reached (queue entity
 * [AttachmentQueue.ENTITY_TYPE]), the same steps as the desktop's offline image upload: upload the
 * stored copy, record that it went through (a retry never uploads twice), and for an image added
 * to the notes put `[[image:N]]` at their end.
 */
class AttachmentUploadSync(
    private val api: VikunjaApiService,
    private val pendingActionDao: PendingActionDao,
    private val attachmentDao: AttachmentDao,
    private val attachmentMapper: AttachmentMapper,
    private val taskDao: TaskDao,
    private val files: OfflineAttachmentFiles,
) {
    fun handles(action: PendingActionEntity): Boolean = action.entityType == AttachmentQueue.ENTITY_TYPE

    /**
     * Sends [action]. A refusal is written into the action (the failed row says why) and thrown
     * on, so the engine marks it failed; a failure worth retrying is thrown unchanged.
     */
    suspend fun process(action: PendingActionEntity, tempIdMap: Map<Long, Long>) {
        val taskId = tempIdMap[action.entityId] ?: action.entityId
        var upload = AttachmentQueue.decode(action) ?: run {
            Logger.w(TAG, "Dropped unreadable upload ${action.id}")
            return
        }
        // The task is being deleted: there is nothing to attach the file to.
        if (pendingActionDao.getActiveByEntity("task", taskId).any { it.actionType == "delete" }) {
            files.delete(upload.file)
            return
        }
        try {
            if (!upload.uploaded) {
                if (!files.exists(upload.file)) {
                    throw IllegalStateException("the copy kept on this device is gone")
                }
                val before = api.getAttachments(taskId).map { it.id }.toSet()
                api.uploadAttachment(taskId, upload.name, upload.size) { files.readChannel(upload.file) }
                val after = api.getAttachments(taskId)
                attachmentDao.replaceForTask(taskId, after.map { with(attachmentMapper) { it.toEntity(taskId) } })
                val made = after.filter { it.id !in before }.maxByOrNull { it.id }?.id
                upload = upload.copy(uploaded = true, attachmentId = made, error = null)
                pendingActionDao.updatePayload(action.id, AttachmentQueue.encode(upload))
                files.delete(upload.file)
            }
            val attachmentId = upload.attachmentId
            if (upload.intoNotes && attachmentId != null) addToNotes(taskId, attachmentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refusal(e, upload)?.let { (message, tooLarge) ->
                pendingActionDao.updatePayload(action.id, AttachmentQueue.encode(upload.copy(error = message, tooLarge = tooLarge)))
            }
            throw e
        }
    }

    /** What the failed row says, or null when [e] is not a refusal (the engine retries or resolves it). */
    private fun refusal(e: Exception, upload: QueuedUpload): Pair<String, Boolean>? {
        if (isRetriableNetworkError(e)) return null
        if (e is VikunjaApiException) {
            return when (e.httpStatus) {
                401, 404 -> null
                413 -> "${formatByteSize(upload.size)}, more than the server accepts" to true
                else -> (e.message?.takeIf { it.isNotBlank() } ?: "the server refused it") to false
            }
        }
        if (e is IllegalStateException) return (e.message ?: "the file could not be read") to false
        return null
    }

    /**
     * Puts the image at the end of the notes: on the server's notes when nothing for the task waits
     * in the queue, else on the local row with a queued patch that joins what waits, so a queued
     * edit of the notes cannot overwrite it.
     */
    private suspend fun addToNotes(taskId: Long, attachmentId: Long) {
        val token = ImageTokens.imageToken(attachmentId)
        val row = taskDao.getByIdSync(taskId)
        if (pendingActionDao.hasWaitingForTask(taskId)) {
            if (row == null || row.description.contains(token)) return
            val description = ImageTokens.appendImageToken(row.description, attachmentId)
            taskDao.upsert(row.copy(description = description))
            val now = DateUtils.nowIso()
            pendingActionDao.queuePatchActionMerging(
                PendingActionEntity(
                    entityType = "task",
                    entityId = taskId,
                    actionType = "update",
                    payload = buildJsonObject { put("description", description) }.toString(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return
        }
        val current = api.getTask(taskId).description.orEmpty()
        if (current.contains(token)) return
        val updated = api.updateTask(taskId, buildJsonObject { put("description", ImageTokens.appendImageToken(current, attachmentId)) })
        if (row != null) taskDao.upsert(row.copy(description = updated.description.orEmpty()))
    }

    /** Deletes stored copies no waiting or failed upload needs any more (discarded, signed out, crashed). */
    suspend fun sweep() {
        try {
            val keep = pendingActionDao.getActiveOfType(AttachmentQueue.ENTITY_TYPE)
                .mapNotNullTo(HashSet()) { AttachmentQueue.decode(it)?.file }
            val removed = files.sweep(keep)
            if (removed > 0) Logger.d(TAG, "Deleted $removed offline attachment copies nothing needs")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Offline attachment sweep failed: ${e.message}")
        }
    }

    /** Uploads of the task created offline as [tempId] now go to [realId]. */
    suspend fun remapTask(tempId: Long, realId: Long) {
        for (a in pendingActionDao.getRemappable()) {
            if (a.entityType == AttachmentQueue.ENTITY_TYPE && a.entityId == tempId) {
                pendingActionDao.remapEntity(a.id, realId, a.payload, "pending")
            }
        }
    }

    private companion object {
        const val TAG = "AttachmentUploadSync"
    }
}

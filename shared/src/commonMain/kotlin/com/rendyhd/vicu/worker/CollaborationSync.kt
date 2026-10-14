package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.data.local.dao.CollabQueue
import com.rendyhd.vicu.data.local.dao.CollaborationDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskCommentEntity
import com.rendyhd.vicu.data.remote.api.TaskCommentDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.Logger

/**
 * Replays queued assignee and comment changes (docs/cross-app-semantics-v1.md section 9.6) for
 * [SyncEngine]. Both kinds are queued per task (`entityId` is the task id); a comment written
 * offline carries a temporary comment id until its create has been sent, and the real id is
 * remembered with the task ids ([tempIdMap]).
 */
class CollaborationSync(
    private val api: VikunjaApiService,
    private val collaborationDao: CollaborationDao,
    private val pendingActionDao: PendingActionDao,
    private val tokenStorage: TokenStorage? = null,
) {
    companion object {
        private const val TAG = "CollaborationSync"

        /** A create that may have reached the server is looked for among comments this much older than the queue entry. */
        private const val DUPLICATE_SLACK_MS = 10 * 60 * 1000L
    }

    fun handles(action: PendingActionEntity): Boolean = action.entityType in CollabQueue.ENTITY_TYPES

    private fun TaskCommentDto.toEntity(taskId: Long) = TaskCommentEntity(
        id = id,
        taskId = taskId,
        authorId = author?.id ?: 0,
        authorUsername = author?.username ?: "",
        authorName = author?.name ?: "",
        comment = comment,
        created = DateUtils.normalizeToUtc(created),
        updated = DateUtils.normalizeToUtc(updated),
    )

    /**
     * Sends [action]. Returns false when it has to wait: it edits or deletes a comment whose create
     * has not been sent yet (that create is still in the queue).
     */
    suspend fun process(action: PendingActionEntity, tempIdMap: MutableMap<Long, Long>, rememberRealId: suspend (Long, Long) -> Unit): Boolean {
        val taskId = tempIdMap[action.entityId] ?: action.entityId
        when (action.entityType) {
            CollabQueue.ASSIGNEE -> {
                val payload = CollabQueue.assignee(action.payload) ?: return true
                if (action.actionType == CollabQueue.ADD) api.addAssignee(taskId, payload.userId) else api.removeAssignee(taskId, payload.userId)
            }
            CollabQueue.COMMENT -> {
                val payload = CollabQueue.comment(action.payload) ?: return true
                when (action.actionType) {
                    CollabQueue.CREATE -> {
                        val created = (if (payload.maybeSent) findSent(taskId, payload.comment, action.createdAt) else null)
                            ?: api.createComment(taskId, payload.comment)
                        collaborationDao.adoptComment(payload.commentId, created.toEntity(taskId))
                        if (payload.commentId < 0) {
                            tempIdMap[payload.commentId] = created.id
                            rememberRealId(payload.commentId, created.id)
                        }
                    }
                    CollabQueue.UPDATE, CollabQueue.DELETE -> {
                        val commentId = tempIdMap[payload.commentId] ?: payload.commentId
                        if (commentId < 0) return !createWaiting(action.entityId, payload.commentId)
                        if (action.actionType == CollabQueue.UPDATE) {
                            val saved = api.updateComment(taskId, commentId, payload.comment)
                            val previous = collaborationDao.getComment(commentId)
                            val entity = saved.toEntity(taskId)
                            collaborationDao.upsertComments(
                                listOf(
                                    if (saved.author == null && previous != null) {
                                        entity.copy(authorId = previous.authorId, authorUsername = previous.authorUsername, authorName = previous.authorName)
                                    } else {
                                        entity
                                    },
                                ),
                            )
                        } else {
                            api.deleteComment(taskId, commentId)
                        }
                    }
                }
            }
        }
        return true
    }

    /** True while the create of comment [tempCommentId] is still in the queue (not discarded). */
    private suspend fun createWaiting(taskId: Long, tempCommentId: Long): Boolean =
        pendingActionDao.getActiveForTask(CollabQueue.COMMENT, taskId).any {
            it.actionType == CollabQueue.CREATE && CollabQueue.comment(it.payload)?.commentId == tempCommentId
        }

    /** The comment an earlier attempt of this create made, if the server has it. */
    private suspend fun findSent(taskId: Long, html: String, queuedAt: String): TaskCommentDto? = try {
        val me = tokenStorage?.getUserId()
        val since = (DateUtils.parseIsoDate(queuedAt)?.toEpochMilliseconds() ?: 0L) - DUPLICATE_SLACK_MS
        api.getTaskCommentsPage(taskId, 1).items.firstOrNull { comment ->
            comment.comment == html &&
                (me == null || comment.author?.id == me) &&
                (DateUtils.parseIsoDate(comment.created)?.toEpochMilliseconds() ?: 0L) >= since
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Logger.w(TAG, "Could not look for an earlier attempt of a comment: ${e.message}")
        null
    }

    /**
     * A refusal that means the change is done or can never apply: unassigning someone who is not
     * assigned, deleting a comment that is gone, editing a comment that is gone (its local copy
     * goes too). Returns true when the action is finished.
     */
    suspend fun resolveMissing(action: PendingActionEntity, e: VikunjaApiException, tempIdMap: Map<Long, Long>): Boolean {
        if (e.httpStatus != 404) return false
        return when {
            action.entityType == CollabQueue.ASSIGNEE && action.actionType == CollabQueue.REMOVE -> true
            action.entityType == CollabQueue.COMMENT && action.actionType == CollabQueue.DELETE -> true
            action.entityType == CollabQueue.COMMENT && action.actionType == CollabQueue.UPDATE &&
                e.problem?.code == VikunjaApiService.ERROR_COMMENT_DOES_NOT_EXIST -> {
                CollabQueue.comment(action.payload)?.let { collaborationDao.deleteComment(tempIdMap[it.commentId] ?: it.commentId) }
                true
            }
            else -> false
        }
    }

    /** A task created offline got its real id: its comments and signals move with it. */
    suspend fun remapTask(tempId: Long, realId: Long) {
        collaborationDao.remapCommentTask(tempId, realId)
        collaborationDao.remapSignalTask(tempId, realId)
        for (a in pendingActionDao.getRemappable()) {
            if (a.entityType in CollabQueue.ENTITY_TYPES && a.entityId == tempId) {
                pendingActionDao.remapEntity(a.id, realId, a.payload, "pending")
            }
        }
    }
}

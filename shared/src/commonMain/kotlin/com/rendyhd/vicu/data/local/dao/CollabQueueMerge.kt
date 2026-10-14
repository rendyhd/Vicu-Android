package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Queued collaboration changes (docs/cross-app-semantics-v1.md section 9.6). Both kinds are queued
 * per task: `entityId` is the task id, so a task created offline moves them to its real id with
 * its other changes.
 *
 *  - `assignee` / `add` | `remove`, payload [AssigneePayload];
 *  - `comment` / `create` | `update` | `delete`, payload [CommentPayload]. A comment written
 *    offline has a negative temporary id until its create has been sent.
 */
object CollabQueue {
    const val ASSIGNEE = "assignee"
    const val COMMENT = "comment"
    const val ADD = "add"
    const val REMOVE = "remove"
    const val CREATE = "create"
    const val UPDATE = "update"
    const val DELETE = "delete"

    val ENTITY_TYPES = setOf(ASSIGNEE, COMMENT)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(payload: AssigneePayload): String = json.encodeToString(AssigneePayload.serializer(), payload)
    fun encode(payload: CommentPayload): String = json.encodeToString(CommentPayload.serializer(), payload)
    fun assignee(payload: String): AssigneePayload? = runCatching { json.decodeFromString(AssigneePayload.serializer(), payload) }.getOrNull()
    fun comment(payload: String): CommentPayload? = runCatching { json.decodeFromString(CommentPayload.serializer(), payload) }.getOrNull()
}

@Serializable
data class AssigneePayload(
    @SerialName("user_id") val userId: Long,
    val username: String = "",
    val name: String = "",
)

@Serializable
data class CommentPayload(
    @SerialName("comment_id") val commentId: Long,
    /** HTML; empty for a delete. */
    val comment: String = "",
    /** A create whose earlier attempt may have reached the server: look for it before sending again. */
    @SerialName("maybe_sent") val maybeSent: Boolean = false,
)

/** What queuing a collaboration change does to the queue. */
data class CollabQueueMerge(
    val deleteIds: List<Long> = emptyList(),
    /** Waiting actions whose payload changes, by action id. */
    val rewrites: Map<Long, String> = emptyMap(),
    /** Whether the new action is added. */
    val insert: Boolean = true,
)

/**
 * The same folding rules as the desktop queue (`src/main/offline/queue-merge.ts`): an assign and
 * an unassign of the same person cancel while neither has been sent; an edit of a comment that is
 * still waiting to be created goes into the create, and a later edit replaces a waiting one; a
 * delete of a comment that was never sent removes it and its edits from the queue. Only waiting
 * (`pending`) actions are folded; one being sent or failed stays as it is.
 */
fun resolveCollabQueueMerge(existing: List<PendingActionEntity>, action: PendingActionEntity): CollabQueueMerge {
    val waiting = existing.filter { it.status == "pending" && it.entityType == action.entityType }
    return when (action.entityType) {
        CollabQueue.ASSIGNEE -> {
            val userId = CollabQueue.assignee(action.payload)?.userId ?: return CollabQueueMerge()
            val same = waiting.filter { CollabQueue.assignee(it.payload)?.userId == userId }
            val opposite = same.filter { it.actionType != action.actionType }
            when {
                opposite.isNotEmpty() -> CollabQueueMerge(deleteIds = opposite.map { it.id }, insert = false)
                same.any { it.actionType == action.actionType } -> CollabQueueMerge(insert = false)
                else -> CollabQueueMerge()
            }
        }
        CollabQueue.COMMENT -> {
            val payload = CollabQueue.comment(action.payload) ?: return CollabQueueMerge()
            val forComment = waiting.filter { CollabQueue.comment(it.payload)?.commentId == payload.commentId }
            val create = forComment.firstOrNull { it.actionType == CollabQueue.CREATE }
            val edits = forComment.filter { it.actionType == CollabQueue.UPDATE }
            when (action.actionType) {
                CollabQueue.UPDATE -> when {
                    create != null -> {
                        val folded = CollabQueue.comment(create.payload)!!.copy(comment = payload.comment)
                        CollabQueueMerge(rewrites = mapOf(create.id to CollabQueue.encode(folded)), insert = false)
                    }
                    edits.isNotEmpty() -> CollabQueueMerge(
                        deleteIds = edits.drop(1).map { it.id },
                        rewrites = mapOf(edits.first().id to action.payload),
                        insert = false,
                    )
                    else -> CollabQueueMerge()
                }
                CollabQueue.DELETE -> when {
                    create != null -> CollabQueueMerge(deleteIds = (listOf(create) + edits).map { it.id }, insert = false)
                    else -> CollabQueueMerge(deleteIds = edits.map { it.id })
                }
                else -> CollabQueueMerge()
            }
        }
        else -> CollabQueueMerge()
    }
}

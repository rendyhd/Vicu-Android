package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Queued relation changes (Pro's "after", docs/cross-app-semantics-v1.md section 21.2): entity type
 * [ENTITY_TYPE], action [ADD] or [REMOVE], `entityId` the task the relation is set on (the
 * desktop's `taskId`), payload [RelationPayload] with the other task and the kind (`blocked`).
 * Either task may be a temporary id until its create has been sent; the replay rewrites both.
 */
object RelationQueue {
    const val ENTITY_TYPE = "relation"
    const val ADD = "add"
    const val REMOVE = "remove"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(payload: RelationPayload): String = json.encodeToString(RelationPayload.serializer(), payload)

    fun decode(payload: String): RelationPayload? =
        runCatching { json.decodeFromString(RelationPayload.serializer(), payload) }.getOrNull()
}

@Serializable
data class RelationPayload(
    @SerialName("other_task_id") val otherTaskId: Long,
    val kind: String,
)

/**
 * The desktop's folding rules (`mergeAddRelation` / `mergeRemoveRelation` in
 * `src/main/offline/queue-merge.ts`): the same change of the same relation already waiting or being
 * sent is not queued again, and an add and a removal of the same relation cancel while the earlier
 * one is still waiting. One being sent or failed stays as it is.
 */
fun resolveRelationQueueMerge(existing: List<PendingActionEntity>, action: PendingActionEntity): CollabQueueMerge {
    val payload = RelationQueue.decode(action.payload) ?: return CollabQueueMerge()
    val same = existing.filter {
        it.entityType == RelationQueue.ENTITY_TYPE && it.entityId == action.entityId && RelationQueue.decode(it.payload) == payload
    }
    if (same.any { it.actionType == action.actionType && it.status in setOf("pending", "processing") }) {
        return CollabQueueMerge(insert = false)
    }
    val opposite = same.filter { it.actionType != action.actionType && it.status == "pending" }
    if (opposite.isNotEmpty()) return CollabQueueMerge(deleteIds = opposite.map { it.id }, insert = false)
    return CollabQueueMerge()
}

/**
 * A relation action with [tempId] replaced by [realId], on the task it is set on or in the payload;
 * null when it names neither.
 */
fun remapRelationAction(action: PendingActionEntity, tempId: Long, realId: Long): PendingActionEntity? {
    if (action.entityType != RelationQueue.ENTITY_TYPE) return null
    val payload = RelationQueue.decode(action.payload) ?: return null
    if (action.entityId != tempId && payload.otherTaskId != tempId) return null
    return action.copy(
        entityId = if (action.entityId == tempId) realId else action.entityId,
        payload = if (payload.otherTaskId == tempId) RelationQueue.encode(payload.copy(otherTaskId = realId)) else action.payload,
    )
}

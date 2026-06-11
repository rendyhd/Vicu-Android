package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity

sealed class QueueMergeOp {
    /** No pending create for this entity — normal dedup: replace its rows with the new action. */
    object ReplaceForEntity : QueueMergeOp()

    /** A pending create exists — fold the new full-task payload into the create row. */
    data class UpdateCreatePayload(val createActionId: Long, val newPayload: String) : QueueMergeOp()

    /** A pending create exists and the entity was deleted — the server never needs to know. */
    object DropAll : QueueMergeOp()
}

/**
 * Decide how to queue a non-create task action when the entity may already have a pending
 * "create" (an offline-created task with a temp id). Both create and update/toggle payloads
 * are the complete serialized Task, so folding is a straight payload swap; SyncWorker's
 * create replay handles the done flag separately (CreateTaskDto has no done field).
 */
fun resolveTaskQueueMerge(
    existing: List<PendingActionEntity>,
    actionType: String,
    payload: String,
): QueueMergeOp {
    val create = existing.firstOrNull { it.actionType == "create" }
        ?: return QueueMergeOp.ReplaceForEntity
    return when (actionType) {
        "update", "toggle_done" -> QueueMergeOp.UpdateCreatePayload(create.id, payload)
        "delete" -> QueueMergeOp.DropAll
        else -> QueueMergeOp.ReplaceForEntity
    }
}

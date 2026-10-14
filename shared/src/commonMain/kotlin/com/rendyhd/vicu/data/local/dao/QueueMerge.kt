package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed class QueueMergeOp {
    /** No pending create for this entity — normal dedup: replace its rows with the new action. */
    object ReplaceForEntity : QueueMergeOp()

    /** A pending create exists — fold the new full-task payload into the create row. */
    data class UpdateCreatePayload(val createActionId: Long, val newPayload: String) : QueueMergeOp()

    /** A pending create exists and the entity was deleted — the server never needs to know. */
    object DropAll : QueueMergeOp()

    /**
     * The create is being sent right now, so its payload can no longer change what reaches the
     * server. The new action is queued on its own (merged with any other waiting action of the
     * entity, never with the create); the sync engine moves it to the real id once the create
     * has succeeded.
     */
    data class QueueBehindCreate(val createActionId: Long) : QueueMergeOp()
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
    if (create.status == "processing" && actionType in setOf("update", "toggle_done", "delete")) {
        return QueueMergeOp.QueueBehindCreate(create.id)
    }
    return when (actionType) {
        "update", "toggle_done" -> QueueMergeOp.UpdateCreatePayload(create.id, payload)
        "delete" -> QueueMergeOp.DropAll
        else -> QueueMergeOp.ReplaceForEntity
    }
}

fun mergePatchPayloads(first: String, second: String, entityType: String = ""): String {
    return runCatching {
        val firstObject = Json.parseToJsonElement(
            normalizeQueuedPatchPayload(entityType, first),
        ) as JsonObject
        val secondObject = Json.parseToJsonElement(
            normalizeQueuedPatchPayload(entityType, second),
        ) as JsonObject
        JsonObject(firstObject + secondObject).toString()
    }.getOrDefault(second)
}

fun normalizeQueuedPatchPayload(entityType: String, payload: String): String {
    return runCatching {
        val payloadObject = Json.parseToJsonElement(payload) as JsonObject
        if ("id" !in payloadObject) {
            // Already a patch. Task patches queued before the issue #30 fix may still carry
            // blank reminder fields that API v2 rejects.
            return if (entityType == "task" && "reminders" in payloadObject) {
                MergePatches.sanitizeTaskPatch(payloadObject).toString()
            } else {
                payload
            }
        }
        val patch = when (entityType) {
            "task" -> MergePatches.task(
                previous = null,
                current = Json.decodeFromString(Task.serializer(), payload),
            )
            "label" -> MergePatches.label(
                previous = null,
                current = Json.decodeFromString(Label.serializer(), payload),
            )
            else -> return payload
        }
        patch.toString()
    }.getOrDefault(payload)
}

package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.util.SubtaskOrder
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** The order of a task's subtasks, a synced setting of the account (docs/cross-app-semantics-v1.md, section 19). */
class SubtaskOrderPreferences(private val synced: SyncedEntries) {

    suspend fun orderOf(taskId: Long): List<Long> =
        SubtaskOrder.read(synced.entries.first()[SyncedPrefKeys.subtaskOrderOf(taskId)])

    suspend fun set(taskId: Long, ids: List<Long>) {
        synced.set(SyncedPrefKeys.subtaskOrderOf(taskId), JsonArray(ids.map { JsonPrimitive(it) }))
    }
}

package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService

/**
 * Makes the label cache match the server's list, deletions included: a label deleted on another
 * device leaves the cache and the cached tasks that carried it. Labels with a queued change (an
 * offline create, rename or delete) are left alone, so the user's edit is not undone before it
 * reaches the server.
 */
class LabelRefresher(
    private val labelDao: LabelDao,
    private val taskDao: TaskDao,
    private val pendingActionDao: PendingActionDao,
    private val api: VikunjaApiService,
    private val labelMapper: LabelMapper,
    private val taskMapper: TaskMapper,
) {
    suspend fun refresh() {
        val server = api.getAllLabels().map { with(labelMapper) { it.toEntity() } }
        val queued = pendingActionDao.getLabelIdsWithPendingActions().toSet()
        labelDao.upsertAll(server.filter { it.id !in queued })

        val serverIds = server.mapTo(HashSet()) { it.id }
        val gone = labelDao.getAllSync().map { it.id }.filter { it !in serverIds && it !in queued }
        if (gone.isEmpty()) return
        labelDao.deleteByIds(gone)
        gone.forEach { stripFromTasks(it) }
    }

    private suspend fun stripFromTasks(labelId: Long) {
        // LIKE is only a pre-filter (it also matches ids that merely start with this one); the
        // exact removal below leaves other rows' chips as they are.
        for (task in taskDao.getByLabelsJsonLike("%\"id\":$labelId%")) {
            val stripped = with(taskMapper) { task.withLabelRemoved(labelId) }
            if (stripped != task) taskDao.upsert(stripped)
        }
    }
}

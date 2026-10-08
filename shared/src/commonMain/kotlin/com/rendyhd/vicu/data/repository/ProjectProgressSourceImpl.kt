package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.sync.CarrierFinder
import com.rendyhd.vicu.data.sync.CarrierSpec
import com.rendyhd.vicu.data.sync.ProjectTaskCounts
import com.rendyhd.vicu.domain.model.ProjectTally
import com.rendyhd.vicu.domain.repository.ProjectProgressSource
import com.rendyhd.vicu.util.RoutineEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * The open side of a project's progress comes from Room, the done side from one cached request
 * ([ProjectTaskCounts]) minus the hidden tasks that are done tasks of the project but not the
 * user's: the routine carriers the phone holds, the synced custom list carrier and the routine
 * archive parts this process has seen. All of those are known without a request, apart from the
 * custom list carrier after a cold start (read once, by the ids remembered) and archive parts,
 * which are only known once the routines screen has loaded them.
 */
class ProjectProgressSourceImpl(
    private val taskDao: TaskDao,
    private val counts: ProjectTaskCounts,
    private val carrierFinder: CarrierFinder,
    private val archiveStore: RoutineArchiveStore,
    private val authManager: AuthManager,
    private val json: Json,
) : ProjectProgressSource {

    override fun observeTallies(): Flow<Map<Long, ProjectTally>> =
        taskDao.observeProjectTallies().map { rows ->
            rows.associate { it.projectId to ProjectTally(open = it.open, doneOnPhone = it.doneOnPhone) }
        }

    override suspend fun doneCount(projectId: Long, tally: ProjectTally): Long? {
        val total = counts.doneTotal(projectId, tally) ?: return null
        val hidden = hiddenDone()[projectId] ?: 0
        return (total - hidden).coerceAtLeast(0)
    }

    /** The hidden done tasks this app knows of, per project; a task known twice counts once. */
    private suspend fun hiddenDone(): Map<Long, Int> {
        val projectOfTask = HashMap<Long, Long>()
        // Main routine carriers (and any archive part that happens to be cached).
        taskDao.getDoneMetadataRefs().forEach { projectOfTask[it.id] = it.projectId }
        val server = authManager.getVikunjaUrl().orEmpty()
        // A cold start has read no carrier yet: read the remembered ones once.
        carrierFinder.ensureKnown(CarrierSpec.CUSTOM_LISTS, server)
        carrierFinder.knownProjects(CarrierSpec.CUSTOM_LISTS, server).forEach { (id, project) -> projectOfTask[id] = project }

        // An archive part lives in the project of its routine's main carrier.
        val parts = archiveStore.knownPartRoutines().filterKeys { it !in projectOfTask }
        if (parts.isNotEmpty()) {
            val projectOfRoutine = HashMap<String, Long>()
            taskDao.getRoutineCarriersSync().forEach { entity ->
                val parsed = RoutineEnvelope.parse(entity.description, json)
                parsed.payload?.let { projectOfRoutine[it.definition.id] = entity.projectId }
            }
            parts.forEach { (taskId, routineId) -> projectOfRoutine[routineId]?.let { projectOfTask[taskId] = it } }
        }
        return projectOfTask.values.groupingBy { it }.eachCount()
    }
}

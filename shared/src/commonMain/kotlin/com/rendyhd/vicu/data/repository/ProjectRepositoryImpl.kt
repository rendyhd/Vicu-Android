package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.ProjectRefresher
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isNetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

class ProjectRepositoryImpl(
    private val projectDao: ProjectDao,
    private val api: VikunjaApiService,
    private val projectMapper: ProjectMapper,
    private val projectRefresher: ProjectRefresher,
    private val pendingActionDao: PendingActionDao,
    private val platformHooks: PlatformRepositoryHooks,
    /** Where Room rows are mapped to domain models: off the main thread; tests pass an unconfined one. */
    private val mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Which projects are drawn without an icon (synced settings); none without it. */
    private val projectIcons: ProjectIconPreferences? = null,
    /** One change per project at a time, and none past a queued one (shared with the other repositories). */
    private val writeGate: TaskWriteGate = TaskWriteGate(pendingActionDao),
) : ProjectRepository {

    private val withoutIcon: Flow<Set<Long>> = projectIcons?.withoutIcon ?: flowOf(emptySet())

    private fun Project.withIcon(hidden: Set<Long>): Project = if (id in hidden) copy(showIcon = false) else this

    private companion object {
        const val ENTITY_TYPE = "project"
    }

    override fun getAll(): Flow<List<Project>> =
        combine(projectDao.getAll(), withoutIcon) { entities, hidden ->
            entities.map { with(projectMapper) { it.toDomain() }.withIcon(hidden) }
        }.flowOn(mappingDispatcher)

    override fun getAllIncludingArchived(): Flow<List<Project>> =
        combine(projectDao.getAllIncludingArchived(), withoutIcon) { entities, hidden ->
            entities.map { with(projectMapper) { it.toDomain() }.withIcon(hidden) }
        }.flowOn(mappingDispatcher)

    override fun getById(id: Long): Flow<Project?> =
        combine(projectDao.getById(id), withoutIcon) { entity, hidden ->
            entity?.let { with(projectMapper) { it.toDomain() }.withIcon(hidden) }
        }.flowOn(mappingDispatcher)

    override fun getChildren(parentId: Long): Flow<List<Project>> =
        combine(projectDao.getChildren(parentId), withoutIcon) { entities, hidden ->
            entities.map { with(projectMapper) { it.toDomain() }.withIcon(hidden) }
        }.flowOn(mappingDispatcher)

    override suspend fun create(project: Project): NetworkResult<Project> {
        return try {
            val dto = with(projectMapper) { project.toCreateDto() }
            val responseDto = api.createProject(dto)
            val entity = with(projectMapper) { responseDto.toEntity() }
            projectDao.upsert(entity)
            NetworkResult.Success(with(projectMapper) { entity.toDomain() })
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to create project")
        }
    }

    /**
     * Changes the project. The local row changes at once; the patch goes to the server, and when
     * that cannot be reached (offline, timeout, a server error worth retrying) it is queued and
     * replayed by the sync engine, folded with any other queued changes of this project. A change
     * the server refuses for good is rolled back and reported.
     */
    override suspend fun update(project: Project): NetworkResult<Project> {
        val previous = projectDao.getByIdSync(project.id)
        val previousProject = previous?.let { with(projectMapper) { it.toDomain() } }
        with(projectMapper) { projectDao.upsert(project.toEntity()) }
        val patch = MergePatches.project(previousProject, project)
        if (patch.isEmpty()) return NetworkResult.Success(project)

        // While a change for this project waits in the queue or is being sent, this one must not
        // overtake it, or the older value would be replayed over the newer one: it joins the queue
        // instead. Changes to one project go one at a time, so one in flight cannot be overtaken
        // either. (A change that failed for good does not hold the project back; the user retries
        // or discards it.) Cancelled, the change is queued: a merge patch can be sent twice.
        return writeGate.sendOrQueueEntity(
            entityType = ENTITY_TYPE,
            entityId = project.id,
            send = {
                val responseDto = api.updateProject(project.id, patch)
                val responseEntity = with(projectMapper) { responseDto.toEntity() }
                projectDao.upsert(responseEntity)
                NetworkResult.Success(with(projectMapper) { responseEntity.toDomain() })
            },
            queue = {
                queuePatch(project.id, patch)
                NetworkResult.Success(project)
            },
            refused = { e ->
                previous?.let { projectDao.upsert(it) }
                NetworkResult.Error(e.message ?: "Failed to update project")
            },
        )
    }

    private suspend fun queuePatch(projectId: Long, patch: JsonObject) {
        val now = DateUtils.nowIso()
        pendingActionDao.queuePatchActionMerging(
            PendingActionEntity(
                entityType = ENTITY_TYPE,
                entityId = projectId,
                actionType = "update",
                payload = patch.toString(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        platformHooks.triggerSync()
    }

    override suspend fun delete(projectId: Long): NetworkResult<Unit> {
        return try {
            api.deleteProject(projectId)
            projectDao.deleteById(projectId)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to delete project")
        }
    }

    override suspend fun refreshAll(): NetworkResult<Unit> {
        return try {
            projectRefresher.refresh()
            NetworkResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkResult.Error(
                message = if (isNetworkFailure(e)) "Can't reach the server" else e.message ?: "Failed to refresh projects",
                code = (e as? VikunjaApiException)?.httpStatus,
                offline = isNetworkFailure(e),
            )
        }
    }
}

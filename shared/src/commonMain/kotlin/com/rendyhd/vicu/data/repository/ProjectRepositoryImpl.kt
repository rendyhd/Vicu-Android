package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.ProjectRefresher
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isNetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProjectRepositoryImpl(
    private val projectDao: ProjectDao,
    private val api: VikunjaApiService,
    private val projectMapper: ProjectMapper,
    private val projectRefresher: ProjectRefresher,
) : ProjectRepository {

    override fun getAll(): Flow<List<Project>> =
        projectDao.getAll().map { entities ->
            entities.map { with(projectMapper) { it.toDomain() } }
        }

    override fun getAllIncludingArchived(): Flow<List<Project>> =
        projectDao.getAllIncludingArchived().map { entities ->
            entities.map { with(projectMapper) { it.toDomain() } }
        }

    override fun getById(id: Long): Flow<Project?> =
        projectDao.getById(id).map { entity ->
            entity?.let { with(projectMapper) { it.toDomain() } }
        }

    override fun getChildren(parentId: Long): Flow<List<Project>> =
        projectDao.getChildren(parentId).map { entities ->
            entities.map { with(projectMapper) { it.toDomain() } }
        }

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

    override suspend fun update(project: Project): NetworkResult<Project> {
        val previous = projectDao.getByIdSync(project.id)
        with(projectMapper) { projectDao.upsert(project.toEntity()) }
        val previousProject = previous?.let { with(projectMapper) { it.toDomain() } }
        val patch = MergePatches.project(previousProject, project)
        if (patch.isEmpty()) return NetworkResult.Success(project)
        return try {
            val responseDto = api.updateProject(project.id, patch)
            val responseEntity = with(projectMapper) { responseDto.toEntity() }
            projectDao.upsert(responseEntity)
            NetworkResult.Success(with(projectMapper) { responseEntity.toDomain() })
        } catch (e: Exception) {
            previous?.let { projectDao.upsert(it) }
            NetworkResult.Error(e.message ?: "Failed to update project")
        }
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

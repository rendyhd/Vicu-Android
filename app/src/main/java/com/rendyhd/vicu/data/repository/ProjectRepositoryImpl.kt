package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
    private val api: VikunjaApiService,
    private val projectMapper: ProjectMapper,
) : ProjectRepository {

    override fun getAll(): Flow<List<Project>> =
        projectDao.getAll().map { entities ->
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

    override suspend fun create(project: Project): NetworkResult<Project> =
        NetworkResult.Error("Not yet implemented")

    override suspend fun update(project: Project): NetworkResult<Project> {
        return try {
            val dto = with(projectMapper) { project.toDto() }
            val entity = with(projectMapper) { dto.toEntity() }
            projectDao.upsert(entity)
            val responseDto = api.updateProject(project.id, dto)
            val responseEntity = with(projectMapper) { responseDto.toEntity() }
            projectDao.upsert(responseEntity)
            NetworkResult.Success(with(projectMapper) { responseEntity.toDomain() })
        } catch (_: Exception) {
            // Optimistic update already saved locally
            NetworkResult.Success(project)
        }
    }

    override suspend fun delete(projectId: Long): NetworkResult<Unit> =
        NetworkResult.Error("Not yet implemented")

    override suspend fun refreshAll(): NetworkResult<Unit> {
        return try {
            val dtos = api.getAllProjects()
            val entities = dtos.map { with(projectMapper) { it.toEntity() } }
            projectDao.upsertAll(entities)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to refresh projects")
        }
    }
}

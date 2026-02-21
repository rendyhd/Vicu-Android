package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun getAll(): Flow<List<Project>>
    fun getById(id: Long): Flow<Project?>
    fun getChildren(parentId: Long): Flow<List<Project>>

    suspend fun create(project: Project): NetworkResult<Project>
    suspend fun update(project: Project): NetworkResult<Project>
    suspend fun delete(projectId: Long): NetworkResult<Unit>
    suspend fun refreshAll(): NetworkResult<Unit>
}

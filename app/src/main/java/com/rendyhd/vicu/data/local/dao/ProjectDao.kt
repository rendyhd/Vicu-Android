package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY position ASC")
    fun getAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getById(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE parentProjectId = :parentId ORDER BY position ASC")
    fun getChildren(parentId: Long): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY position ASC")
    suspend fun getAllSync(): List<ProjectEntity>

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Upsert
    suspend fun upsertAll(projects: List<ProjectEntity>)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

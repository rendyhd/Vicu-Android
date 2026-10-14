package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects WHERE isArchived = 0 ORDER BY position ASC")
    fun getAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY position ASC")
    fun getAllIncludingArchived(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getById(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE parentProjectId = :parentId AND isArchived = 0 ORDER BY position ASC")
    fun getChildren(parentId: Long): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE isArchived = 0 ORDER BY position ASC")
    suspend fun getAllSync(): List<ProjectEntity>

    @Query("SELECT * FROM projects ORDER BY position ASC")
    suspend fun getAllIncludingArchivedSync(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getByIdSync(id: Long): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Upsert
    suspend fun upsertAll(projects: List<ProjectEntity>)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()

    @Query("SELECT id FROM projects")
    suspend fun getAllIds(): List<Long>

    /** At most [MAX_SQL_ID_PARAMS] ids; callers use [deleteByIds]. */
    @Query("DELETE FROM projects WHERE id IN (:ids)")
    suspend fun deleteByIdsChunk(ids: List<Long>)

    /** Any number of ids; runs one statement per [MAX_SQL_ID_PARAMS]. */
    @Transaction
    suspend fun deleteByIds(ids: List<Long>) {
        ids.sqlIdChunks().forEach { deleteByIdsChunk(it) }
    }

    /**
     * Makes the table match the server's [projects]. Projects in [keepIds] (a queued edit that has
     * not reached the server) are neither overwritten nor deleted.
     */
    @Transaction
    suspend fun replaceAll(projects: List<ProjectEntity>, keepIds: Set<Long> = emptySet()) {
        val incoming = projects.filter { it.id !in keepIds }
        if (incoming.isNotEmpty()) upsertAll(incoming)
        val serverIds = projects.mapTo(HashSet()) { it.id }
        deleteByIds(getAllIds().filter { it !in serverIds && it !in keepIds })
    }
}

package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.LabelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelDao {

    @Query("SELECT * FROM labels ORDER BY title ASC")
    fun getAll(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels WHERE id = :id")
    suspend fun getById(id: Long): LabelEntity?

    @Upsert
    suspend fun upsert(label: LabelEntity)

    @Upsert
    suspend fun upsertAll(labels: List<LabelEntity>)

    @Query("SELECT * FROM labels")
    suspend fun getAllSync(): List<LabelEntity>

    @Query("DELETE FROM labels WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** At most [MAX_SQL_ID_PARAMS] ids; callers use [deleteByIds]. */
    @Query("DELETE FROM labels WHERE id IN (:ids)")
    suspend fun deleteByIdsChunk(ids: List<Long>)

    /** Any number of ids; runs one statement per [MAX_SQL_ID_PARAMS], atomically. */
    @Transaction
    suspend fun deleteByIds(ids: List<Long>) {
        ids.sqlIdChunks().forEach { deleteByIdsChunk(it) }
    }

    @Query("DELETE FROM labels")
    suspend fun deleteAll()
}

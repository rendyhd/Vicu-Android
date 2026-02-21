package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
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

    @Query("DELETE FROM labels WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM labels")
    suspend fun deleteAll()
}

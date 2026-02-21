package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE taskId = :taskId")
    fun getByTaskId(taskId: Long): Flow<List<AttachmentEntity>>

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Upsert
    suspend fun upsertAll(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: Long)
}

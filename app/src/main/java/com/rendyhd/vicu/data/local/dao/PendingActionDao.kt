package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingActionDao {

    @Query("SELECT * FROM pending_actions WHERE status = 'pending' ORDER BY createdAt ASC")
    fun getPending(): Flow<List<PendingActionEntity>>

    @Query("SELECT COUNT(*) FROM pending_actions WHERE status = 'pending'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT * FROM pending_actions WHERE status = 'pending' AND retryCount < maxRetries")
    suspend fun getRetryable(): List<PendingActionEntity>

    @Insert
    suspend fun insert(action: PendingActionEntity): Long

    @Query("UPDATE pending_actions SET status = :status WHERE id = :id")
    suspend fun updateStatusOnly(id: Long, status: String)

    @Query("UPDATE pending_actions SET status = :status, retryCount = :retryCount WHERE id = :id")
    suspend fun updateStatusAndRetry(id: Long, status: String, retryCount: Int)

    suspend fun updateStatus(id: Long, status: String, retryCount: Int? = null) {
        if (retryCount != null) {
            updateStatusAndRetry(id, status, retryCount)
        } else {
            updateStatusOnly(id, status)
        }
    }

    @Query("DELETE FROM pending_actions WHERE status = 'completed'")
    suspend fun deleteCompleted()

    @Query("DELETE FROM pending_actions WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteByEntity(entityType: String, entityId: Long)

    @Query("SELECT * FROM pending_actions WHERE status = 'failed' ORDER BY createdAt ASC")
    fun getFailed(): Flow<List<PendingActionEntity>>

    @Query("SELECT COUNT(*) FROM pending_actions WHERE status = 'failed'")
    fun getFailedCount(): Flow<Int>

    @Query("DELETE FROM pending_actions WHERE status = 'failed'")
    suspend fun deleteFailed()

    @Query("UPDATE pending_actions SET status = 'pending', retryCount = 0 WHERE status = 'failed'")
    suspend fun retryAllFailed()

    @Transaction
    suspend fun replaceForEntity(entityType: String, entityId: Long, action: PendingActionEntity) {
        deleteByEntity(entityType, entityId)
        insert(action)
    }
}

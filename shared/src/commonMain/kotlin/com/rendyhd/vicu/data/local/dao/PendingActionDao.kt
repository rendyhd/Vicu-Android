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

    @Query("SELECT * FROM pending_actions WHERE status = 'pending' AND retryCount < maxRetries ORDER BY createdAt ASC, id ASC")
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

    /** Marks an action failed for good; [failedAt] starts the clock for [deleteFailedBefore]. */
    @Query("UPDATE pending_actions SET status = 'failed', updatedAt = :failedAt WHERE id = :id")
    suspend fun markFailed(id: Long, failedAt: String)

    /** Drops failed actions that failed before [cutoff] (an ISO-8601 UTC timestamp). */
    @Query("DELETE FROM pending_actions WHERE status = 'failed' AND updatedAt < :cutoff")
    suspend fun deleteFailedBefore(cutoff: String)

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

    @Query("UPDATE pending_actions SET status = 'pending' WHERE status = 'processing'")
    suspend fun resetProcessingToPending()

    /**
     * Ids of the tasks whose local row holds a change the server has not accepted yet. A refresh
     * must not overwrite these rows.
     *
     * Failed actions count too, on purpose: the row is the only copy of the user's change until
     * they retry or discard it from the failed-changes banner. They do not protect it for ever:
     * the sync engine drops failed actions older than its failed-action retention (14 days) at the
     * start of each run, and discarding deletes them at once, so the server version wins after
     * the next refresh in both cases.
     */
    @Query("SELECT entityId FROM pending_actions WHERE entityType IN ('task', 'routine') AND status IN ('pending', 'failed', 'processing')")
    suspend fun getTaskIdsWithPendingActions(): List<Long>

    @Query("SELECT * FROM pending_actions WHERE status IN ('pending', 'failed')")
    suspend fun getRemappable(): List<PendingActionEntity>

    @Query("UPDATE pending_actions SET entityId = :entityId, payload = :payload, status = :status, retryCount = 0 WHERE id = :id")
    suspend fun remapEntity(id: Long, entityId: Long, payload: String, status: String)

    @Transaction
    suspend fun replaceForEntity(entityType: String, entityId: Long, action: PendingActionEntity) {
        deleteByEntity(entityType, entityId)
        insert(action)
    }

    @Query("SELECT * FROM pending_actions WHERE entityType = :entityType AND entityId = :entityId AND status IN ('pending', 'failed', 'processing')")
    suspend fun getActiveByEntity(entityType: String, entityId: Long): List<PendingActionEntity>

    @Transaction
    suspend fun queueTaskActionMerging(action: PendingActionEntity) {
        val existing = getActiveByEntity(action.entityType, action.entityId)
        when (val op = resolveTaskQueueMerge(existing, action.actionType, action.payload)) {
            QueueMergeOp.ReplaceForEntity -> {
                val mergedPayload = if (action.actionType == "update" || action.actionType == "toggle_done") {
                    existing
                        .filter { it.actionType == "update" || it.actionType == "toggle_done" }
                        .fold(action.payload) { combined, old ->
                            mergePatchPayloads(old.payload, combined, action.entityType)
                        }
                } else {
                    action.payload
                }
                replaceForEntity(
                    action.entityType,
                    action.entityId,
                    action.copy(payload = mergedPayload),
                )
            }
            is QueueMergeOp.UpdateCreatePayload -> remapEntity(op.createActionId, action.entityId, op.newPayload, "pending")
            QueueMergeOp.DropAll -> deleteByEntity(action.entityType, action.entityId)
        }
    }

    @Transaction
    suspend fun queuePatchActionMerging(action: PendingActionEntity) {
        queueTaskActionMerging(action)
    }
}

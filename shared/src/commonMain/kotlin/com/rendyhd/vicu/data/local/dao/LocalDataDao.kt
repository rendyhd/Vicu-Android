package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Bulk deletes for [com.rendyhd.vicu.data.local.LocalDataWiper]. The tables fall into two groups:
 *
 *  - caches of server data (tasks, projects, labels, attachments), which a sync rebuilds;
 *  - local-only state that nothing can rebuild: the offline queue (`pending_actions`) and the
 *    phone-only routine history of versions before 1.9 that is waiting to be uploaded to the
 *    server (`routine_occurrence_archive`, empty once the upload has run).
 *
 * Clearing caches must never take the second group with it, and must keep the cached rows that
 * queued actions still refer to (an offline-created task exists only as that row and its queued
 * create), so those deletes use a subquery on the queue instead of a bound id list.
 */
@Dao
interface LocalDataDao {

    @Query(
        """
        DELETE FROM tasks WHERE id NOT IN (
            SELECT entityId FROM pending_actions
            WHERE entityType IN ('task', 'routine') AND status IN ('pending', 'failed', 'processing')
        )
        """
    )
    suspend fun deleteTasksWithoutQueuedActions()

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM projects")
    suspend fun deleteAllProjects()

    @Query(
        """
        DELETE FROM labels WHERE id NOT IN (
            SELECT entityId FROM pending_actions
            WHERE entityType = 'label' AND status IN ('pending', 'failed', 'processing')
        )
        """
    )
    suspend fun deleteLabelsWithoutQueuedActions()

    @Query("DELETE FROM labels")
    suspend fun deleteAllLabels()

    @Query("DELETE FROM attachments")
    suspend fun deleteAllAttachments()

    @Query("DELETE FROM pending_actions")
    suspend fun deleteAllPendingActions()

    @Query("DELETE FROM routine_occurrence_archive")
    suspend fun deleteAllRoutineArchive()

    /** Entries of phone-only routine history that have not been uploaded yet. */
    @Query("SELECT COUNT(*) FROM routine_occurrence_archive")
    fun observeRoutineArchiveCount(): Flow<Int>

    /** Queued changes that have not reached the server: waiting, in flight or failed. */
    @Query("SELECT COUNT(*) FROM pending_actions WHERE status IN ('pending', 'failed', 'processing')")
    suspend fun countUnsyncedActions(): Int

    /** Cache tables only. Rows the offline queue refers to, the queue and the routine archive stay. */
    @Transaction
    suspend fun clearCaches() {
        deleteTasksWithoutQueuedActions()
        deleteAllProjects()
        deleteLabelsWithoutQueuedActions()
        deleteAllAttachments()
    }

    /** Drops the offline queue and every cache table, but keeps the routine history archive. */
    @Transaction
    suspend fun discardUnsyncedAndClearCaches() {
        deleteAllPendingActions()
        deleteAllTasks()
        deleteAllProjects()
        deleteAllLabels()
        deleteAllAttachments()
    }

    /** Every table: the account is gone (sign-out, or a different account signed in). */
    @Transaction
    suspend fun clearEverything() {
        deleteAllPendingActions()
        deleteAllTasks()
        deleteAllProjects()
        deleteAllLabels()
        deleteAllAttachments()
        deleteAllRoutineArchive()
    }
}

package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.RoutineOccurrenceArchiveEntity
import kotlinx.coroutines.flow.Flow

/** The phone-only history waiting to be uploaded; see [RoutineOccurrenceArchiveEntity]. */
@Dao
interface RoutineArchiveDao {
    @Query("SELECT * FROM routine_occurrence_archive WHERE routineId = :routineId ORDER BY scheduledDate DESC")
    fun observeByRoutine(routineId: String): Flow<List<RoutineOccurrenceArchiveEntity>>

    @Query("SELECT * FROM routine_occurrence_archive WHERE routineId = :routineId ORDER BY scheduledDate DESC")
    suspend fun getByRoutine(routineId: String): List<RoutineOccurrenceArchiveEntity>

    @Query("SELECT * FROM routine_occurrence_archive ORDER BY scheduledDate DESC")
    suspend fun getAll(): List<RoutineOccurrenceArchiveEntity>

    @Upsert
    suspend fun upsertAll(items: List<RoutineOccurrenceArchiveEntity>)

    @Query("DELETE FROM routine_occurrence_archive WHERE routineId = :routineId")
    suspend fun deleteByRoutine(routineId: String)

    @Query("DELETE FROM routine_occurrence_archive")
    suspend fun deleteAll()
}

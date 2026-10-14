package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class RoutineParseIssue(
    val taskId: Long,
    val title: String,
    val message: String,
)

/** The routine history as CSV, and whether it covers all of it. */
data class RoutineCsvExport(
    val csv: String,
    /** False when archived history could not be read (offline), so only the routines' own history is in [csv]. */
    val complete: Boolean = true,
)

interface RoutineRepository {
    fun observeActive(): Flow<List<Routine>>
    fun observeArchived(): Flow<List<Routine>>
    fun observeRoutine(routineId: String): Flow<Routine?>
    fun observeDay(date: String): Flow<RoutineDay>
    fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>>
    fun observeIssues(): Flow<List<RoutineParseIssue>>

    /**
     * A problem with the history archive that did not stop a change from being saved (for
     * example the server refused an archive part), or null. Being offline is not a problem: the
     * history stays in the routine and moves on a later change.
     */
    val archiveWarning: Flow<String?> get() = flowOf(null)

    suspend fun create(draft: RoutineDraft): NetworkResult<Routine>
    suspend fun update(routineId: String, draft: RoutineDraft): NetworkResult<Routine>
    suspend fun setOccurrenceStatus(
        routineId: String,
        date: String,
        slotId: String,
        status: OccurrenceStatus,
        note: String = "",
    ): NetworkResult<Routine>
    suspend fun archive(routineId: String, archived: Boolean): NetworkResult<Routine>
    suspend fun deletePermanently(routineId: String): NetworkResult<Unit>
    suspend fun finalizeAndPrune(): NetworkResult<Unit>
    suspend fun exportCsv(): String

    /** Like [exportCsv], but says when archived history could not be included. */
    suspend fun exportCsvWithStatus(): RoutineCsvExport = RoutineCsvExport(exportCsv())

    /**
     * Uploads history that versions before 1.9 kept only on this phone into archive parts on the
     * server, merged with the parts already there, then forgets the local copy. Safe to run again
     * at any time: it does nothing once the phone-only table is empty, and a run that was cut
     * short or failed offline continues where it stopped. With [carriersAuthoritative] (the
     * carrier cache was just refreshed from the server) history of a routine that no longer
     * exists is dropped instead of kept for later.
     */
    suspend fun migrateLocalArchive(carriersAuthoritative: Boolean = false): NetworkResult<Unit> =
        NetworkResult.Success(Unit)
}

package com.rendyhd.vicu.worker

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.repository.RoutineParseIssue
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Routine history that older versions kept only on the phone is uploaded from the sync run, so a
 * first launch that was offline gets another try as soon as the phone is online.
 */
class SyncEngineRoutineHistoryTest {

    private class RecordingRoutines(
        private val outcome: () -> NetworkResult<Unit> = { NetworkResult.Success(Unit) },
    ) : RoutineRepository {
        val migrations = mutableListOf<Boolean>()

        override suspend fun migrateLocalArchive(carriersAuthoritative: Boolean): NetworkResult<Unit> {
            migrations += carriersAuthoritative
            return outcome()
        }

        override fun observeActive(): Flow<List<Routine>> = flowOf(emptyList())
        override fun observeArchived(): Flow<List<Routine>> = flowOf(emptyList())
        override fun observeRoutine(routineId: String): Flow<Routine?> = flowOf(null)
        override fun observeDay(date: String): Flow<RoutineDay> = flowOf(RoutineDay(date, emptyList()))
        override fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>> = flowOf(emptyList())
        override fun observeIssues(): Flow<List<RoutineParseIssue>> = flowOf(emptyList())
        override suspend fun create(draft: RoutineDraft): NetworkResult<Routine> = NetworkResult.Error("not faked")
        override suspend fun update(routineId: String, draft: RoutineDraft): NetworkResult<Routine> =
            NetworkResult.Error("not faked")

        override suspend fun setOccurrenceStatus(
            routineId: String,
            date: String,
            slotId: String,
            status: OccurrenceStatus,
            note: String,
        ): NetworkResult<Routine> = NetworkResult.Error("not faked")

        override suspend fun archive(routineId: String, archived: Boolean): NetworkResult<Routine> =
            NetworkResult.Error("not faked")

        override suspend fun deletePermanently(routineId: String): NetworkResult<Unit> = NetworkResult.Success(Unit)
        override suspend fun finalizeAndPrune(): NetworkResult<Unit> = NetworkResult.Success(Unit)
        override suspend fun exportCsv(): String = ""
    }

    @Test
    fun `a sync that refreshed everything uploads the history and trusts the carrier cache`() = runTest {
        val routines = RecordingRoutines()
        val h = SyncEngineHarness(routines = routines) { emptyPage() }

        assertTrue(h.engine.performSync())

        assertEquals(listOf(true), routines.migrations)
        h.close()
    }

    @Test
    fun `a sync whose refresh failed still tries the upload but does not trust the cache`() = runTest {
        val routines = RecordingRoutines()
        val h = SyncEngineHarness(routines = routines) { request ->
            if (request.method == HttpMethod.Get && request.url.encodedPath == "/tasks") {
                respond("", HttpStatusCode.InternalServerError)
            } else {
                emptyPage()
            }
        }

        h.engine.performSync()

        assertEquals(listOf(false), routines.migrations)
        h.close()
    }

    @Test
    fun `an upload that fails or throws never fails the sync`() = runTest {
        val failing = RecordingRoutines { NetworkResult.Error("server said no") }
        val throwing = RecordingRoutines { error("boom") }

        for (routines in listOf(failing, throwing)) {
            val h = SyncEngineHarness(routines = routines) { emptyPage() }

            assertTrue(h.engine.performSync(), "the sync itself went fine")

            assertEquals(1, routines.migrations.size)
            h.close()
        }
    }

    @Test
    fun `a sync without a routine repository still works`() = runTest {
        val h = SyncEngineHarness { emptyPage() }

        assertTrue(h.engine.performSync())
        h.close()
    }
}

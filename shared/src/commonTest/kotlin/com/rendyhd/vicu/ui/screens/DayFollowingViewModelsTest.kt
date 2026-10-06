package com.rendyhd.vicu.ui.screens

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.repository.RecordingRepositoryHooks
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.repository.RoutineParseIssue
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.screens.routines.RoutinesViewModel
import com.rendyhd.vicu.ui.screens.today.TodayViewModel
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.SchedulerTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** After midnight the routine screens show and log the new day, not the one they started on. */
@OptIn(ExperimentalCoroutinesApi::class)
class DayFollowingViewModelsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    /** Answers observeDay(date) with an empty day for that date and counts finalize calls. */
    private class FakeRoutineRepository : RoutineRepository {
        val observedDates = mutableListOf<String>()
        var finalizeCalls = 0

        override fun observeActive(): Flow<List<Routine>> = flowOf(emptyList())
        override fun observeArchived(): Flow<List<Routine>> = flowOf(emptyList())
        override fun observeRoutine(routineId: String): Flow<Routine?> = flowOf(null)
        override fun observeDay(date: String): Flow<RoutineDay> {
            observedDates += date
            return MutableStateFlow(date).map { RoutineDay(it, emptyList()) }
        }

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
        override suspend fun finalizeAndPrune(): NetworkResult<Unit> {
            finalizeCalls++
            return NetworkResult.Success(Unit)
        }

        override suspend fun exportCsv(): String = ""
    }

    private fun TestScope.dayClock(start: String) =
        DayClock(backgroundScope, SchedulerTimeSource(testScheduler, Instant.parse(start), TimeZone.UTC))

    @Test
    fun `the routines screen moves to the next day at midnight`() = runTest {
        val repository = FakeRoutineRepository()
        val vm = RoutinesViewModel(
            repository = repository,
            prefsStore = RoutinePrefsStore(InMemoryPreferencesDataStore()),
            platformHooks = RecordingRepositoryHooks(),
            dayClock = dayClock("2026-10-06T23:30:00Z"),
        )
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertEquals("2026-10-06", vm.uiState.value.day.date)
        assertEquals(1, repository.finalizeCalls)

        advanceTimeBy(31.minutes)
        runCurrent()

        assertEquals("2026-10-07", vm.uiState.value.day.date)
        assertEquals(listOf("2026-10-06", "2026-10-07"), repository.observedDates)
        assertEquals(2, repository.finalizeCalls, "yesterday's open occurrences are closed out")
    }

    @Test
    fun `the routines screen starts on the clock's day, not a frozen one`() = runTest {
        val repository = FakeRoutineRepository()
        val vm = RoutinesViewModel(
            repository = repository,
            prefsStore = RoutinePrefsStore(InMemoryPreferencesDataStore()),
            platformHooks = RecordingRepositoryHooks(),
            dayClock = dayClock("2026-12-31T08:00:00Z"),
        )

        assertEquals("2026-12-31", vm.uiState.value.day.date)
    }

    @Test
    fun `the Today routine block moves to the next day at midnight`() = runTest {
        val repository = FakeRoutineRepository()
        val authScope = CoroutineScope(SupervisorJob())
        val vm = TodayViewModel(
            taskRepository = FakeTaskRepository(),
            projectRepository = FakeProjectRepository(),
            labelRepository = FakeLabelRepository(),
            routineRepository = repository,
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            syncStaleness = SyncStaleness().also { it.markSynced() },
            dayClock = dayClock("2026-10-06T22:00:00Z"),
        )
        runCurrent()
        assertEquals("2026-10-06", vm.uiState.value.routineDay.date)

        advanceTimeBy(2.hours + 1.minutes)
        runCurrent()

        assertEquals("2026-10-07", vm.uiState.value.routineDay.date)
        authScope.cancel()
    }
}

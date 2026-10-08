package com.rendyhd.vicu.ui.navigation

import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.LabelOrderPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.model.ProjectTally
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.ProjectProgress
import com.rendyhd.vicu.util.SchedulerTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/**
 * The drawer's progress rings: numbers are asked for only while the drawer is open and only for
 * the rows on screen, once per project, and again only when that project's tasks change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModelProgressTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private class NoCustomLists : CustomListRepository {
        override val lists = MutableStateFlow<List<CustomList>>(emptyList())
        override val syncStatus: StateFlow<CustomListSyncStatus> = MutableStateFlow(CustomListSyncStatus.Idle)
        override suspend fun upsert(customList: CustomList) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun reorder(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun clearLocal() = Unit
        override suspend fun sync(): CustomListSyncStatus = CustomListSyncStatus.Idle
    }

    private class Rig(val viewModel: DrawerViewModel, val source: FakeProjectProgressSource, private val scope: CoroutineScope) {
        val progress get() = viewModel.projectProgress.value
        /** Ends the view model's own coroutines while Dispatchers.Main is still the test one. */
        fun close() {
            viewModel.viewModelScope.cancel()
            scope.cancel()
        }
    }

    private fun TestScope.rig(source: FakeProjectProgressSource): Rig {
        val authScope = CoroutineScope(SupervisorJob())
        val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = InMemoryTokenStorage(),
            apiServiceProvider = { error("no network in this test") },
            appScope = authScope,
            networkMonitor = FakeNetworkMonitor(),
        )
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-08T10:00:00Z"), TimeZone.UTC)
        val vm = DrawerViewModel(
            projectRepository = FakeProjectRepository(emptyList()),
            labelRepository = FakeLabelRepository(emptyList()),
            customListRepository = NoCustomLists(),
            authManager = auth,
            bottomBarPrefsStore = BottomBarPrefsStore(InMemoryPreferencesDataStore()),
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            routinePrefsStore = RoutinePrefsStore(InMemoryPreferencesDataStore()),
            labelOrderPrefsStore = LabelOrderPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            dayClock = DayClock(backgroundScope, time),
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            appMessages = AppMessages(),
            progressSource = source,
        )
        backgroundScope.launch { vm.projectProgress.collect { } }
        runCurrent()
        return Rig(vm, source, authScope)
    }

    private fun TestScope.settle() {
        advanceTimeBy(PROGRESS_SETTLE_MS + 1)
        runCurrent()
    }

    private fun source() = FakeProjectProgressSource(
        initial = mapOf(1L to ProjectTally(open = 2, doneOnPhone = 0), 2L to ProjectTally(open = 1, doneOnPhone = 1)),
        doneByProject = mutableMapOf(1L to 3L, 2L to 4L),
    )

    @Test
    fun `nothing is asked while the drawer is closed`() = runTest {
        val rig = rig(source())
        settle()

        assertEquals(emptyList(), rig.source.asked)
        assertEquals(emptyMap(), rig.progress)
        rig.close()
    }

    @Test
    fun `a project on screen gets a ring of its done tasks out of all of them`() = runTest {
        val rig = rig(source())

        rig.viewModel.setProgressRows(setOf(1L))
        settle()

        assertEquals(mapOf(1L to ProjectProgress(done = 3, total = 5)), rig.progress)
        assertEquals(listOf(1L), rig.source.asked.map { it.first }, "only the row on screen is asked for")
        rig.close()
    }

    @Test
    fun `scrolling another row into view asks for it alone`() = runTest {
        val rig = rig(source())
        rig.viewModel.setProgressRows(setOf(1L))
        settle()

        rig.viewModel.setProgressRows(setOf(1L, 2L))
        settle()

        assertEquals(listOf(1L, 2L), rig.source.asked.map { it.first })
        assertEquals(setOf(1L, 2L), rig.progress.keys)
        rig.close()
    }

    @Test
    fun `a settled burst of changes to one project asks once and leaves the others alone`() = runTest {
        val rig = rig(source())
        rig.viewModel.setProgressRows(setOf(1L, 2L))
        settle()
        rig.source.asked.clear()

        // A sync writes several rows of project 1 in quick succession.
        rig.source.tallies.value = rig.source.tallies.value + (1L to ProjectTally(open = 1, doneOnPhone = 1))
        advanceTimeBy(PROGRESS_SETTLE_MS / 5)
        rig.source.tallies.value = rig.source.tallies.value + (1L to ProjectTally(open = 0, doneOnPhone = 2))
        settle()

        assertEquals(listOf(1L to ProjectTally(open = 0, doneOnPhone = 2)), rig.source.asked)
        rig.close()
    }

    @Test
    fun `the open side of the ring follows the local tasks at once`() = runTest {
        val rig = rig(source())
        rig.viewModel.setProgressRows(setOf(1L))
        settle()

        rig.source.tallies.value = rig.source.tallies.value + (1L to ProjectTally(open = 7, doneOnPhone = 0))
        runCurrent()

        assertEquals(ProjectProgress(done = 3, total = 10), rig.progress[1L])
        rig.close()
    }

    @Test
    fun `closing and opening the drawer asks again, and the source decides whether that costs a request`() = runTest {
        val rig = rig(source())
        rig.viewModel.setProgressRows(setOf(1L))
        settle()

        rig.viewModel.setProgressRows(emptySet())
        settle()
        rig.viewModel.setProgressRows(setOf(1L))
        settle()

        assertEquals(listOf(1L, 1L), rig.source.asked.map { it.first })
        rig.close()
    }

    @Test
    fun `a count that cannot be read leaves the ring out`() = runTest {
        val source = source().also { it.doneByProject[1L] = null }
        val rig = rig(source)

        rig.viewModel.setProgressRows(setOf(1L, 2L))
        settle()

        assertEquals(setOf(2L), rig.progress.keys)
        rig.close()
    }

    @Test
    fun `a project without any task has no ring`() = runTest {
        val source = FakeProjectProgressSource(doneByProject = mutableMapOf(5L to 0L))
        val rig = rig(source)

        rig.viewModel.setProgressRows(setOf(5L))
        settle()

        assertEquals(listOf(5L to ProjectTally.EMPTY), source.asked)
        assertEquals(emptyMap(), rig.progress)
        rig.close()
    }
}

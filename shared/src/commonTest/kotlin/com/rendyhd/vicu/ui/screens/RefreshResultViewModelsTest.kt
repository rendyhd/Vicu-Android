package com.rendyhd.vicu.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProjectSectionPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LogbookPage
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.screens.logbook.LogbookViewModel
import com.rendyhd.vicu.ui.screens.project.ProjectViewModel
import com.rendyhd.vicu.ui.screens.search.SearchViewModel
import com.rendyhd.vicu.ui.screens.tag.TagViewModel
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.ui.screens.shared.testProjectActions
import com.rendyhd.vicu.util.AppMessages

/**
 * A list screen no longer swallows the result of its refresh: a failure is shown (an offline one
 * only when the user pulled down), the app is not marked fresh after it, and the screen keeps
 * showing the error and the spinner state when the lists emit again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshResultViewModelsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val label = Label(id = 7, title = "errands")
    private val offline = NetworkResult.Error("Can't reach the server", offline = true)
    private val serverError = NetworkResult.Error("Server error", code = 500)

    private class Rig {
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository(listOf(Project(id = 1, title = "Project")))
        val labels = FakeLabelRepository(listOf(Label(id = 7, title = "errands")))
        val staleness = SyncStaleness()
        val refresher = ScreenRefresher(tasks, projects, labels, staleness)
    }

    private fun Rig.tagViewModel() = TagViewModel(
        savedStateHandle = SavedStateHandle(mapOf("labelId" to label.id)),
        taskRepository = tasks,
        projectRepository = projects,
        labelRepository = labels,
        refresher = refresher,
    )

    private fun Rig.logbookViewModel() = LogbookViewModel(
        taskRepository = tasks,
        projectRepository = projects,
        labelRepository = labels,
        refresher = refresher,
    )

    private fun Rig.projectViewModel() = ProjectViewModel(
        savedStateHandle = SavedStateHandle(mapOf("projectId" to 1L)),
        taskRepository = tasks,
        projectRepository = projects,
        labelRepository = labels,
        refresher = refresher,
        behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
        projectSectionPrefsStore = ProjectSectionPrefsStore(InMemoryPreferencesDataStore()),
        projectActions = testProjectActions(projects),
        reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
        appMessages = AppMessages(),
    )

    // --- what the screen shows -------------------------------------------------------------

    @Test
    fun `a failed refresh when the screen opens is shown and the app stays stale`() = runTest {
        val rig = Rig().apply { tasks.refreshResult = serverError }

        val vm = rig.tagViewModel()
        runCurrent()

        assertEquals("Server error", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isRefreshing)
        assertTrue(rig.refresher.isStale(), "a failed refresh must not make the next screen skip its own")
    }

    @Test
    fun `an offline refresh when the screen opens stays quiet`() = runTest {
        val rig = Rig().apply { tasks.refreshResult = offline }

        val vm = rig.tagViewModel()
        runCurrent()

        assertNull(vm.uiState.value.error, "the cached tasks are shown; the sync indicator says offline")
        assertTrue(rig.refresher.isStale())
    }

    @Test
    fun `pulling down while offline says so, and asks for a full reconcile`() = runTest {
        val rig = Rig().apply { staleness.markSynced() }
        val vm = rig.tagViewModel()
        runCurrent()
        rig.tasks.refreshResult = offline

        vm.refresh(showSpinner = true)
        runCurrent()

        assertEquals("Can't reach the server", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isRefreshing)
        assertEquals(listOf(true), rig.tasks.fullRefreshes, "only the pull refreshed, and it asked for a full reconcile")
    }

    @Test
    fun `a refresh that works clears the old error and marks the app fresh`() = runTest {
        val rig = Rig().apply { tasks.refreshResult = serverError }
        val vm = rig.tagViewModel()
        runCurrent()
        assertEquals("Server error", vm.uiState.value.error)
        vm.clearError()
        rig.tasks.refreshResult = NetworkResult.Success(Unit)

        vm.refresh(showSpinner = true)
        runCurrent()

        assertNull(vm.uiState.value.error)
        assertFalse(rig.refresher.isStale())
    }

    @Test
    fun `an error from a refresh is not wiped when the project lists emit again`() = runTest {
        val rig = Rig().apply { tasks.refreshResult = serverError }
        val vm = rig.projectViewModel()
        runCurrent()
        assertEquals("Server error", vm.uiState.value.error)

        // A task arrives (as it would from the refresh itself): the screen re-renders its lists.
        rig.tasks.put(Task(id = 1, title = "Arrives", projectId = 1))
        runCurrent()

        assertEquals("Server error", vm.uiState.value.error, "the snackbar has not been shown yet")
    }

    @Test
    fun `the spinner of a pull to refresh survives the lists emitting while it runs`() = runTest {
        val rig = Rig().apply { staleness.markSynced() }
        val gate = CompletableDeferred<Unit>()
        val vm = rig.projectViewModel()
        runCurrent()
        rig.tasks.refreshGate = gate

        vm.refresh(showSpinner = true)
        runCurrent()
        assertTrue(vm.uiState.value.isRefreshing)
        rig.tasks.put(Task(id = 2, title = "Arrives mid-refresh", projectId = 1))
        runCurrent()

        assertTrue(vm.uiState.value.isRefreshing, "a list emission must not end the spinner early")
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.isRefreshing)
    }

    // --- Logbook paging --------------------------------------------------------------------

    @Test
    fun `the logbook loads its first page when it opens and offers more while the server has more`() = runTest {
        val rig = Rig().apply {
            staleness.markSynced()
            tasks.logbookResult = { NetworkResult.Success(LogbookPage(it, hasMore = true)) }
        }

        val vm = rig.logbookViewModel()
        runCurrent()

        assertEquals(listOf(1), rig.tasks.logbookPages)
        assertTrue(vm.uiState.value.hasMore)
        assertEquals(1, vm.uiState.value.pagesLoaded)
    }

    @Test
    fun `reaching the end of the logbook loads the next page until there are no more`() = runTest {
        val rig = Rig().apply {
            staleness.markSynced()
            tasks.logbookResult = { NetworkResult.Success(LogbookPage(it, hasMore = it < 3)) }
        }
        val vm = rig.logbookViewModel()
        runCurrent()

        vm.loadMore()
        runCurrent()
        vm.loadMore()
        runCurrent()
        vm.loadMore()
        runCurrent()

        assertEquals(listOf(1, 2, 3), rig.tasks.logbookPages, "the third call found nothing more to ask for")
        assertFalse(vm.uiState.value.hasMore)
        assertEquals(3, vm.uiState.value.pagesLoaded)
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    @Test
    fun `a second request for a page while one is loading is ignored`() = runTest {
        val rig = Rig().apply {
            staleness.markSynced()
            tasks.logbookResult = { NetworkResult.Success(LogbookPage(it, hasMore = true)) }
        }
        val vm = rig.logbookViewModel()
        runCurrent()

        vm.loadMore()
        vm.loadMore()
        assertTrue(vm.uiState.value.isLoadingMore)
        runCurrent()

        assertEquals(listOf(1, 2), rig.tasks.logbookPages)
    }

    @Test
    fun `a page that fails is reported and can be asked for again`() = runTest {
        val rig = Rig().apply {
            staleness.markSynced()
            tasks.logbookResult = { page ->
                if (page == 2) serverError else NetworkResult.Success(LogbookPage(page, hasMore = true))
            }
        }
        val vm = rig.logbookViewModel()
        runCurrent()

        vm.loadMore()
        runCurrent()

        assertEquals("Server error", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoadingMore)
        assertEquals(1, vm.uiState.value.pagesLoaded, "the failed page does not count as loaded")
        assertTrue(vm.uiState.value.hasMore)

        rig.tasks.logbookResult = { NetworkResult.Success(LogbookPage(it, hasMore = false)) }
        vm.clearError()
        vm.loadMore()
        runCurrent()
        assertEquals(2, vm.uiState.value.pagesLoaded)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun `pulling down in the logbook reloads the first page and reports a failure of the refresh`() = runTest {
        val rig = Rig().apply {
            staleness.markSynced()
            tasks.logbookResult = { NetworkResult.Success(LogbookPage(it, hasMore = true)) }
        }
        val vm = rig.logbookViewModel()
        runCurrent()
        rig.tasks.logbookPages.clear()
        rig.tasks.refreshResult = serverError

        vm.refresh(showSpinner = true)
        runCurrent()

        assertEquals("Server error", vm.uiState.value.error)
        assertEquals(listOf(1), rig.tasks.logbookPages)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `an offline pull in the logbook does not try the completed page as well`() = runTest {
        val rig = Rig().apply { staleness.markSynced() }
        val vm = rig.logbookViewModel()
        runCurrent()
        rig.tasks.logbookPages.clear()
        rig.tasks.refreshResult = offline

        vm.refresh(showSpinner = true)
        runCurrent()

        assertEquals("Can't reach the server", vm.uiState.value.error)
        assertTrue(rig.tasks.logbookPages.isEmpty(), "a second attempt would fail the same way")
    }

    // --- Search ----------------------------------------------------------------------------

    @Test
    fun `a search whose server request fails says so, unless the device is offline`() = runTest {
        val tasks = FakeTaskRepository()
        val vm = SearchViewModel(tasks, FakeProjectRepository(), FakeLabelRepository())
        runCurrent()

        tasks.refreshResult = offline
        vm.onQueryChanged("milk")
        advanceTimeBy(400)
        runCurrent()
        assertNull(vm.uiState.value.error, "offline search shows the cached matches")

        tasks.refreshResult = serverError
        vm.onQueryChanged("milks")
        advanceTimeBy(400)
        runCurrent()
        assertEquals("Server error", vm.uiState.value.error)
    }
}

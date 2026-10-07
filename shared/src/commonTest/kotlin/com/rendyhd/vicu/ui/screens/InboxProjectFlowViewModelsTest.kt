package com.rendyhd.vicu.ui.screens

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.LabelOrderPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.fakeScreenRefresher
import com.rendyhd.vicu.ui.navigation.DrawerViewModel
import com.rendyhd.vicu.ui.screens.anytime.AnytimeViewModel
import com.rendyhd.vicu.ui.screens.inbox.InboxViewModel
import com.rendyhd.vicu.ui.screens.review.ReviewViewModel
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.SchedulerTimeSource
import com.rendyhd.vicu.worker.FakeCustomListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screens that depend on the Inbox project observe it instead of reading it once, so an Inbox
 * chosen in Settings (or set after a sign-in that left it open) reaches them without reopening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxProjectFlowViewModelsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val inbox = Project(id = 5, title = "Inbox")
    private val other = Project(id = 6, title = "Other")
    private val work = Project(id = 7, title = "Work")

    /** A real [AuthManager] over in-memory storage: the Inbox id lives in [storage]. */
    private class Auth {
        val storage = InMemoryTokenStorage()
        val scope = CoroutineScope(SupervisorJob())
        val manager = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = storage,
            apiServiceProvider = { error("no network in this test") },
            appScope = scope,
            networkMonitor = FakeNetworkMonitor(),
        )
    }

    private fun taskIn(id: Long, projectId: Long) = Task(id = id, title = "Task $id", projectId = projectId)

    private class Fakes(projects: List<Project>) {
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository(projects)
        val labels = FakeLabelRepository()
    }

    // --- Inbox screen ----------------------------------------------------------------------

    @Test
    fun `the inbox screen follows an inbox that is chosen after it opened`() = runTest {
        val auth = Auth() // sign-in finished, project selection not yet
        val fakes = Fakes(listOf(inbox, other)).apply {
            tasks.put(taskIn(1, 5))
            tasks.put(taskIn(2, 6))
        }
        val vm = InboxViewModel(
            fakes.tasks, fakes.projects, fakes.labels, auth.manager,
            fakeScreenRefresher(fakes.tasks, fakes.projects, fakes.labels),
        )
        runCurrent()
        assertNull(vm.uiState.value.inboxProjectId)
        assertFalse(vm.uiState.value.isLoading, "no Inbox yet is not an endless spinner")
        assertTrue(vm.uiState.value.tasks.isEmpty())
        assertEquals("No Inbox project is selected. Choose one in Settings.", vm.uiState.value.error)

        auth.manager.onInboxProjectSelected(5)
        runCurrent()

        assertEquals(5L, vm.uiState.value.inboxProjectId)
        assertEquals(listOf(1L), vm.uiState.value.tasks.map { it.id })
        assertNull(vm.uiState.value.error, "the notice goes away once an Inbox is chosen")
        auth.scope.cancel()
    }

    @Test
    fun `changing the inbox project in Settings switches the open inbox screen`() = runTest {
        val auth = Auth().also { it.storage.storeInboxProjectId(5) }
        val fakes = Fakes(listOf(inbox, other)).apply {
            tasks.put(taskIn(1, 5))
            tasks.put(taskIn(2, 6))
        }
        val vm = InboxViewModel(
            fakes.tasks, fakes.projects, fakes.labels, auth.manager,
            fakeScreenRefresher(fakes.tasks, fakes.projects, fakes.labels),
        )
        runCurrent()
        assertEquals(listOf(1L), vm.uiState.value.tasks.map { it.id })

        auth.manager.onInboxProjectSelected(6)
        runCurrent()

        assertEquals(6L, vm.uiState.value.inboxProjectId)
        assertEquals(listOf(2L), vm.uiState.value.tasks.map { it.id })
        auth.scope.cancel()
    }

    @Test
    fun `an account switch that clears the inbox empties the open inbox screen`() = runTest {
        val auth = Auth().also { it.storage.storeInboxProjectId(5) }
        val fakes = Fakes(listOf(inbox)).apply { tasks.put(taskIn(1, 5)) }
        val vm = InboxViewModel(
            fakes.tasks, fakes.projects, fakes.labels, auth.manager,
            fakeScreenRefresher(fakes.tasks, fakes.projects, fakes.labels),
        )
        runCurrent()
        assertEquals(listOf(1L), vm.uiState.value.tasks.map { it.id })

        auth.storage.clearInboxProjectId()
        runCurrent()

        assertNull(vm.uiState.value.inboxProjectId)
        assertTrue(vm.uiState.value.tasks.isEmpty())
        auth.scope.cancel()
    }

    // --- Anytime ---------------------------------------------------------------------------

    @Test
    fun `anytime leaves out whichever project is the inbox right now`() = runTest {
        val auth = Auth().also { it.storage.storeInboxProjectId(5) }
        val fakes = Fakes(listOf(inbox, other, work)).apply {
            tasks.put(taskIn(1, 5))
            tasks.put(taskIn(2, 6))
            tasks.put(taskIn(3, 7))
        }
        val vm = AnytimeViewModel(
            fakes.tasks, fakes.projects, fakes.labels, auth.manager,
            fakeScreenRefresher(fakes.tasks, fakes.projects, fakes.labels),
        )
        runCurrent()
        assertEquals(setOf(6L, 7L), vm.uiState.value.projectGroups.map { it.project.id }.toSet())

        auth.manager.onInboxProjectSelected(6)
        runCurrent()

        // Project 5 now counts as an ordinary project, and 6 is the inbox that Anytime skips.
        assertEquals(setOf(5L, 7L), vm.uiState.value.projectGroups.map { it.project.id }.toSet())
        auth.scope.cancel()
    }

    // --- Drawer ----------------------------------------------------------------------------

    private fun TestScope.drawer(auth: Auth, fakes: Fakes): DrawerViewModel {
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-06T10:00:00Z"), TimeZone.UTC)
        return DrawerViewModel(
            projectRepository = fakes.projects,
            labelRepository = fakes.labels,
            customListRepository = FakeCustomListRepository(),
            authManager = auth.manager,
            bottomBarPrefsStore = BottomBarPrefsStore(InMemoryPreferencesDataStore()),
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            labelOrderPrefsStore = LabelOrderPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            dayClock = DayClock(backgroundScope, time),
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
        )
    }

    @Test
    fun `the drawer follows the inbox project and lists the former inbox as an ordinary project`() = runTest {
        val auth = Auth().also { it.storage.storeInboxProjectId(5) }
        val fakes = Fakes(listOf(inbox, other))
        val vm = drawer(auth, fakes)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertEquals(5L, vm.uiState.value.inboxProjectId)
        assertEquals(listOf(6L), vm.uiState.value.projectTree.map { it.project.id })

        auth.manager.onInboxProjectSelected(6)
        runCurrent()

        assertEquals(6L, vm.uiState.value.inboxProjectId)
        assertEquals(listOf(5L), vm.uiState.value.projectTree.map { it.project.id })
        auth.scope.cancel()
    }

    @Test
    fun `the drawer shows no inbox project after the account's inbox was cleared`() = runTest {
        val auth = Auth().also { it.storage.storeInboxProjectId(5) }
        val vm = drawer(auth, Fakes(listOf(inbox, other)))
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        auth.storage.clearInboxProjectId()
        runCurrent()

        assertEquals(0L, vm.uiState.value.inboxProjectId)
        assertEquals(setOf(5L, 6L), vm.uiState.value.projectTree.map { it.project.id }.toSet())
        auth.scope.cancel()
    }

    // --- Review ----------------------------------------------------------------------------

    @Test
    fun `review stops excluding a project once it is no longer the inbox`() = runTest {
        val auth = Auth().also { it.storage.storeInboxProjectId(5) }
        val fakes = Fakes(listOf(inbox, other))
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-06T10:00:00Z"), TimeZone.UTC)
        val vm = ReviewViewModel(
            projectRepository = fakes.projects,
            taskRepository = fakes.tasks,
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            authManager = auth.manager,
            dayClock = DayClock(backgroundScope, time),
        )
        runCurrent()
        assertEquals(listOf(6L), vm.uiState.value.all.map { it.project.id })

        auth.manager.onInboxProjectSelected(6)
        runCurrent()

        assertEquals(listOf(5L), vm.uiState.value.all.map { it.project.id })
        auth.scope.cancel()
    }
}

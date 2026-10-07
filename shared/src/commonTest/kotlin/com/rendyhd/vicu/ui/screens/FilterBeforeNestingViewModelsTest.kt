package com.rendyhd.vicu.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.ui.fakeScreenRefresher
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.screens.customlist.CustomListViewModel
import com.rendyhd.vicu.ui.screens.tag.TagViewModel
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.CrossAppFixture.fixture
import com.rendyhd.vicu.util.CrossAppFixture.local
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.SchedulerTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Tag and custom-list screens apply their label and list conditions to the full task set and
 * only then hide nested subtasks, so a subtask that matches is shown even when its parent does
 * not (cross-app semantics, section 3.2; review X-16 and A-UI-12). They also take "today" from
 * the day clock and ask the server with local-day boundaries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilterBeforeNestingViewModelsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val errands = Label(id = 7, title = "errands")
    private val projects = listOf(
        Project(id = 10, title = "Alpha"),
        Project(id = 11, title = "Beta"),
        Project(id = 12, title = "Gamma"),
    )

    private fun task(
        id: Long,
        parent: Task? = null,
        labels: List<Label> = emptyList(),
        priority: Int = 0,
        projectId: Long = 10,
        due: String = "",
        done: Boolean = false,
    ) = Task(
        id = id,
        title = "Task $id",
        projectId = projectId,
        priority = priority,
        labels = labels,
        dueDate = due,
        done = done,
        relatedTasks = if (parent == null) emptyMap() else mapOf(RelationKind.PARENTTASK to listOf(parent)),
    )

    // --- Tag screen -----------------------------------------------------------------------

    private fun tagViewModel(tasks: FakeTaskRepository): TagViewModel {
        val projectRepository = FakeProjectRepository(projects)
        val labels = FakeLabelRepository(listOf(errands))
        return TagViewModel(
            savedStateHandle = SavedStateHandle(mapOf("labelId" to errands.id)),
            taskRepository = tasks,
            projectRepository = projectRepository,
            labelRepository = labels,
            refresher = fakeScreenRefresher(tasks, projectRepository, labels),
        )
    }

    private fun TagViewModel.shownIds() = uiState.value.tasks.map { it.id }

    @Test
    fun `a labeled subtask shows when its open parent does not carry the label`() = runTest {
        val parent = task(1)
        val tasks = FakeTaskRepository().apply {
            put(parent)
            put(task(2, parent = parent, labels = listOf(errands)))
        }
        val vm = tagViewModel(tasks)

        runCurrent()

        assertEquals(listOf(2L), vm.shownIds())
    }

    @Test
    fun `a labeled subtask stays nested under a parent that carries the label too`() = runTest {
        val parent = task(1, labels = listOf(errands))
        val tasks = FakeTaskRepository().apply {
            put(parent)
            put(task(2, parent = parent, labels = listOf(errands)))
            put(task(3, labels = listOf(errands)))
        }
        val vm = tagViewModel(tasks)

        runCurrent()

        assertEquals(listOf(1L, 3L), vm.shownIds())
    }

    @Test
    fun `a labeled subtask of a completed parent shows`() = runTest {
        val parent = task(1, done = true)
        val tasks = FakeTaskRepository().apply {
            put(parent)
            put(task(2, parent = parent, labels = listOf(errands)))
        }
        val vm = tagViewModel(tasks)

        runCurrent()

        assertEquals(listOf(2L), vm.shownIds())
    }

    // --- Custom list screen -----------------------------------------------------------------

    private class StubCustomLists(initial: List<CustomList>) : CustomListRepository {
        val current = MutableStateFlow(initial)
        override val lists = current
        override val syncStatus = MutableStateFlow<CustomListSyncStatus>(CustomListSyncStatus.Idle)
        override suspend fun upsert(customList: CustomList) {
            current.value = current.value.filter { it.id != customList.id } + customList
        }
        override suspend fun delete(id: String) = Unit
        override suspend fun reorder(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun clearLocal() = Unit
        override suspend fun sync(): CustomListSyncStatus = CustomListSyncStatus.Idle
    }

    private class Rig(
        val vm: CustomListViewModel,
        val tasks: FakeTaskRepository,
        val lists: StubCustomLists,
        val time: SchedulerTimeSource,
        val authScope: CoroutineScope,
    )

    private fun TestScope.customListRig(
        filter: CustomListFilter,
        now: String = "2026-10-06T10:00:00",
        zone: TimeZone = TimeZone.of("Europe/Amsterdam"),
    ): Rig {
        val tasks = FakeTaskRepository()
        val lists = StubCustomLists(listOf(CustomList(id = "list-1", name = "List", filter = filter)))
        val authScope = CoroutineScope(SupervisorJob())
        val time = SchedulerTimeSource(testScheduler, local(now, zone), zone)
        val projectRepository = FakeProjectRepository(projects)
        val labels = FakeLabelRepository(listOf(errands))
        val vm = CustomListViewModel(
            savedStateHandle = SavedStateHandle(mapOf("listId" to "list-1")),
            taskRepository = tasks,
            projectRepository = projectRepository,
            labelRepository = labels,
            customListRepository = lists,
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            dayClock = DayClock(backgroundScope, time),
            refresher = fakeScreenRefresher(tasks, projectRepository, labels),
        )
        return Rig(vm, tasks, lists, time, authScope)
    }

    private fun Rig.shownIds() = vm.uiState.value.tasks.map { it.id }

    @Test
    fun `a subtask that matches the list shows when its parent does not match`() = runTest {
        val rig = customListRig(CustomListFilter(priorityFilter = listOf(4)))
        val parent = task(1, priority = 0)
        rig.tasks.put(parent)
        rig.tasks.put(task(2, parent = parent, priority = 4))
        runCurrent()

        assertEquals(listOf(2L), rig.shownIds())
        rig.authScope.cancel()
    }

    @Test
    fun `a matching subtask is hidden under its parent only when the parent matches too`() = runTest {
        val rig = customListRig(CustomListFilter(labelIds = listOf(errands.id)))
        val parent = task(1, labels = listOf(errands))
        rig.tasks.put(parent)
        rig.tasks.put(task(2, parent = parent, labels = listOf(errands)))
        rig.tasks.put(task(3, labels = listOf(errands)))
        runCurrent()

        assertEquals(listOf(1L, 3L), rig.shownIds().sorted())
        rig.authScope.cancel()
    }

    @Test
    fun `a matching subtask of a completed parent shows, and the parent does not unless done tasks are included`() = runTest {
        val rig = customListRig(CustomListFilter(priorityFilter = listOf(4)))
        val parent = task(1, priority = 4, done = true)
        rig.tasks.put(parent)
        rig.tasks.put(task(2, parent = parent, priority = 4))
        runCurrent()
        assertEquals(listOf(2L), rig.shownIds())

        // With done tasks included the parent matches too, so the child nests under it again.
        rig.lists.upsert(CustomList("list-1", "List", filter = CustomListFilter(priorityFilter = listOf(4), includeDone = true)))
        runCurrent()
        assertEquals(listOf(1L), rig.shownIds())
        rig.authScope.cancel()
    }

    @Test
    fun `refreshing a custom list fetches its own tasks and reports a failure`() = runTest {
        val rig = customListRig(CustomListFilter(priorityFilter = listOf(4)))
        runCurrent()
        rig.tasks.refreshes.clear()
        rig.tasks.refreshResult = NetworkResult.Error("Server error", code = 500)

        rig.vm.refresh(showSpinner = true)
        runCurrent()

        assertEquals(1, rig.tasks.refreshes.size, "one filtered fetch, not a refresh of every task")
        assertTrue(rig.tasks.refreshes.single().isNotEmpty())
        assertEquals("Server error", rig.vm.uiState.value.error)
        assertFalse(rig.vm.uiState.value.isRefreshing)
        rig.authScope.cancel()
    }

    /** The fixture tasks as the app holds them (due dates as UTC instants of [zone]). */
    private fun fixtureTasks(zone: TimeZone): List<Task> = fixture.tasks.map {
        task(
            id = it.id,
            projectId = it.projectId,
            priority = it.priority,
            labels = it.labelIds.map { id -> Label(id = id, title = "Label $id") },
            due = it.due?.let { due -> local(due, zone).toString() } ?: Constants.NULL_DATE_STRING,
            done = it.done,
        )
    }

    private fun Rig.putFixtureTasks(zone: TimeZone) = fixtureTasks(zone).forEach { tasks.put(it) }

    private fun vector(name: String) = fixture.customLists.first { it.name == name }.expect

    @Test
    fun `the window follows the day clock, and moves at midnight`() = runTest {
        val zone = TimeZone.of("Europe/Amsterdam")
        val rig = customListRig(CustomListFilter(dueDateFilter = "today", includeOverdue = false), "2026-10-06T23:30:00", zone)
        rig.putFixtureTasks(zone)
        runCurrent()
        assertEquals(vector("today, include_overdue false"), rig.shownIds().sorted(), "Oct 6 at 23:30")

        advanceTimeBy(31.minutes)
        runCurrent()

        // Oct 7: tasks 4 (a legacy midnight value) and 5 are due that day, nothing else is.
        assertEquals(listOf(4L, 5L), rig.shownIds().sorted(), "Oct 7 just after midnight")
        rig.authScope.cancel()
    }

    @Test
    fun `include overdue off drops overdue tasks and on keeps them`() = runTest {
        val zone = TimeZone.of("Europe/Amsterdam")
        val rig = customListRig(CustomListFilter(dueDateFilter = "today", includeOverdue = false), zone = zone)
        rig.putFixtureTasks(zone)
        runCurrent()
        assertEquals(vector("today, include_overdue false"), rig.shownIds().sorted())

        rig.lists.upsert(CustomList("list-1", "List", filter = CustomListFilter(dueDateFilter = "today")))
        runCurrent()
        assertEquals(vector("today, include_overdue absent"), rig.shownIds().sorted())
        rig.authScope.cancel()
    }

    @Test
    fun `the server is asked with local-day boundaries, again when the day changes`() = runTest {
        val zone = TimeZone.of("Europe/Amsterdam")
        val rig = customListRig(CustomListFilter(dueDateFilter = "today"), "2026-10-06T23:30:00", zone)
        runCurrent()

        // Amsterdam is UTC+2: the start of Oct 7 is 22:00 UTC on Oct 6.
        assertEquals(
            "done = false && due_date < '2026-10-06T22:00:00Z' && due_date != '0001-01-01T00:00:00Z'",
            rig.tasks.refreshes.last()["filter"],
        )

        advanceTimeBy(31.minutes)
        runCurrent()

        assertEquals(
            "done = false && due_date < '2026-10-07T22:00:00Z' && due_date != '0001-01-01T00:00:00Z'",
            rig.tasks.refreshes.last()["filter"],
        )
        assertEquals(2, rig.tasks.refreshes.size)
        rig.authScope.cancel()
    }
}

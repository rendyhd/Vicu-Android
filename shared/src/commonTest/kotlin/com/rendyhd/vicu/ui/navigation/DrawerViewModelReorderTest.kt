package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.LabelOrderPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.screens.shared.testProjectActions
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.POSITION_STEP
import com.rendyhd.vicu.util.SchedulerTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertTrue
import com.rendyhd.vicu.data.local.RoutinePrefsStore

/**
 * Dragging in the drawer: the new order shows at once, is saved through the repositories, and a
 * refusal is reported instead of vanishing (UI-28).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModelReorderTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun project(id: Long, parent: Long = 0, position: Double = id * 100.0) =
        Project(id = id, title = "P$id", parentProjectId = parent, position = position)

    private fun customList(id: String) = CustomList(id = id, name = id.uppercase(), filter = CustomListFilter())

    private class RecordingCustomLists(initial: List<CustomList>) : CustomListRepository {
        override val lists = MutableStateFlow(initial)
        override val syncStatus: StateFlow<CustomListSyncStatus> = MutableStateFlow(CustomListSyncStatus.Idle)
        val reorders = mutableListOf<Pair<Int, Int>>()
        var failReorder = false

        override suspend fun upsert(customList: CustomList) = Unit
        override suspend fun delete(id: String) = Unit

        override suspend fun reorder(fromIndex: Int, toIndex: Int) {
            if (failReorder) error("storage is full")
            reorders += fromIndex to toIndex
            lists.value = lists.value.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }

        override suspend fun clearLocal() = Unit
        override suspend fun sync(): CustomListSyncStatus = CustomListSyncStatus.Idle
    }

    private class Rig(
        val projects: FakeProjectRepository,
        val lists: RecordingCustomLists,
        val labels: FakeLabelRepository,
        val labelOrder: LabelOrderPrefsStore,
        val shown: MutableList<String>,
        val viewModel: DrawerViewModel,
        private val authScope: CoroutineScope,
    ) {
        val state get() = viewModel.uiState.value
        val rowIds get() = state.projectRows.map { it.project.id }

        /** What the repository does for real: the saved project is what the drawer reads next. */
        fun saveProjectsToTheCache() {
            projects.updateResult = { saved ->
                projects.projects.value = projects.projects.value.map { if (it.id == saved.id) saved else it }
                NetworkResult.Success(saved)
            }
        }

        fun close() = authScope.cancel()
    }

    private fun TestScope.rig(
        projects: List<Project> = emptyList(),
        lists: List<CustomList> = emptyList(),
        labels: List<Label> = emptyList(),
    ): Rig {
        val authScope = CoroutineScope(SupervisorJob())
        val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = InMemoryTokenStorage(),
            apiServiceProvider = { error("no network in this test") },
            appScope = authScope,
            networkMonitor = FakeNetworkMonitor(),
        )
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-07T10:00:00Z"), TimeZone.UTC)
        val messages = AppMessages()
        val shown = mutableListOf<String>()
        backgroundScope.launch { messages.messages.collect { shown += it.text } }
        val projectRepo = FakeProjectRepository(projects)
        val listRepo = RecordingCustomLists(lists)
        val labelRepo = FakeLabelRepository(labels)
        val labelOrder = LabelOrderPrefsStore(InMemoryPreferencesDataStore())
        val vm = DrawerViewModel(
            projectRepository = projectRepo,
            labelRepository = labelRepo,
            customListRepository = listRepo,
            authManager = auth,
            bottomBarPrefsStore = BottomBarPrefsStore(InMemoryPreferencesDataStore()),
            reviewPrefsStore = ReviewPrefsStore(InMemoryPreferencesDataStore()),
            routinePrefsStore = RoutinePrefsStore(InMemoryPreferencesDataStore()),
            labelOrderPrefsStore = labelOrder,
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            dayClock = DayClock(backgroundScope, time),
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            appMessages = messages,
            progressSource = FakeProjectProgressSource(),
            projectActions = testProjectActions(projectRepo, auth),
        )
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        return Rig(projectRepo, listRepo, labelRepo, labelOrder, shown, vm, authScope)
    }

    // --- projects ---

    @Test
    fun `a drop between two projects sends one position and shows the new order at once`() = runTest {
        val rig = rig(listOf(project(1), project(2), project(3)))

        rig.viewModel.reorderProject(movedId = 3, idsInNewOrder = listOf(1, 3, 2))
        runCurrent()

        assertEquals(listOf(3L to 150.0), rig.projects.updates.map { it.id to it.position })
        assertEquals(listOf(1L, 3L, 2L), rig.rowIds, "before the stored order has caught up")
        rig.close()
    }

    @Test
    fun `once the drop is stored the screen shows what is stored`() = runTest {
        val rig = rig(listOf(project(1), project(2), project(3)))
        rig.saveProjectsToTheCache()

        rig.viewModel.reorderProject(movedId = 3, idsInNewOrder = listOf(1, 3, 2))
        runCurrent()
        assertEquals(listOf(1L, 3L, 2L), rig.rowIds)

        // A later change, from another device say, must not be held back by the order just dropped.
        rig.projects.projects.value = rig.projects.projects.value.map { if (it.id == 2L) it.copy(position = 50.0) else it }
        runCurrent()

        assertEquals(listOf(2L, 1L, 3L), rig.rowIds)
        rig.close()
    }

    @Test
    fun `an order that never reaches the cache is dropped after a while`() = runTest {
        val rig = rig(listOf(project(1), project(2)))

        rig.viewModel.reorderProject(movedId = 2, idsInNewOrder = listOf(2, 1))
        runCurrent()
        assertEquals(listOf(2L, 1L), rig.rowIds)

        advanceTimeBy(PENDING_ORDER_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(listOf(1L, 2L), rig.rowIds, "the list shows what is stored again")
        rig.close()
    }

    @Test
    fun `projects that share a position are renumbered, the dragged one last`() = runTest {
        val rig = rig(listOf(project(1, position = 0.0), project(2, position = 0.0), project(3, position = 0.0)))

        rig.viewModel.reorderProject(movedId = 3, idsInNewOrder = listOf(1, 3, 2))
        runCurrent()

        assertEquals(
            listOf(1L to POSITION_STEP, 2L to 3 * POSITION_STEP, 3L to 2 * POSITION_STEP),
            rig.projects.updates.map { it.id to it.position },
        )
        rig.close()
    }

    @Test
    fun `a refused drop is reported and the list goes back to what is stored`() = runTest {
        val rig = rig(listOf(project(1), project(2), project(3)))
        rig.projects.updateResult = { NetworkResult.Error("Server said no") }

        rig.viewModel.reorderProject(movedId = 3, idsInNewOrder = listOf(1, 3, 2))
        runCurrent()

        assertEquals(listOf("Could not save the new order: Server said no"), rig.shown)
        assertEquals(listOf(1L, 2L, 3L), rig.rowIds)
        rig.close()
    }

    @Test
    fun `the first refusal stops the renumbering`() = runTest {
        val rig = rig(listOf(project(1, position = 0.0), project(2, position = 0.0), project(3, position = 0.0)))
        rig.projects.updateResult = { NetworkResult.Error("Server said no") }

        rig.viewModel.reorderProject(movedId = 3, idsInNewOrder = listOf(1, 3, 2))
        runCurrent()

        assertEquals(1, rig.projects.updates.size, "nothing is sent after a refusal")
        assertEquals(1, rig.shown.size)
        rig.close()
    }

    @Test
    fun `dropping a project where it was changes nothing and says nothing`() = runTest {
        val rig = rig(listOf(project(1), project(2), project(3)))

        rig.viewModel.reorderProject(movedId = 2, idsInNewOrder = listOf(1, 2, 3))
        runCurrent()

        assertTrue(rig.projects.updates.isEmpty())
        assertTrue(rig.shown.isEmpty())
        rig.close()
    }

    @Test
    fun `a project that is no longer listed cannot be dropped`() = runTest {
        val rig = rig(listOf(project(1), project(2)))

        rig.viewModel.reorderProject(movedId = 99, idsInNewOrder = listOf(99, 1, 2))
        runCurrent()

        assertTrue(rig.projects.updates.isEmpty())
        assertTrue(rig.shown.isEmpty())
        rig.close()
    }

    @Test
    fun `a sibling that has gone since the drag began is left out of the plan`() = runTest {
        val rig = rig(listOf(project(1), project(2)))

        rig.viewModel.reorderProject(movedId = 1, idsInNewOrder = listOf(2, 1, 99))
        runCurrent()

        assertEquals(listOf(1L to 200.0 + POSITION_STEP), rig.projects.updates.map { it.id to it.position })
        rig.close()
    }

    @Test
    fun `children are reordered inside their parent`() = runTest {
        val rig = rig(listOf(project(1), project(10, parent = 1, position = 1.0), project(11, parent = 1, position = 2.0)))

        rig.viewModel.reorderProject(movedId = 11, idsInNewOrder = listOf(11, 10))
        runCurrent()

        assertEquals(listOf(11L to 0.5), rig.projects.updates.map { it.id to it.position })
        assertEquals(listOf(1L, 11L, 10L), rig.rowIds)
        rig.close()
    }

    @Test
    fun `the drawer saves a drop through the shared sibling move`() = runTest {
        val rig = rig(listOf(project(1), project(2), project(3)))
        rig.projects.updateResult = { NetworkResult.Error("") }

        rig.viewModel.reorderProject(movedId = 3, idsInNewOrder = listOf(1, 3, 2))
        runCurrent()

        // ProjectActions.moveAmongSiblings plans and sends it; a refusal without a reason says so plainly.
        assertEquals(listOf(3L to 150.0), rig.projects.updates.map { it.id to it.position })
        assertEquals(listOf("Could not save the new order"), rig.shown)
        rig.close()
    }

    // --- new project ---

    @Test
    fun `new project creates it and confirms in the app-wide snackbar`() = runTest {
        val rig = rig(listOf(project(1)))

        rig.viewModel.createProject("Garden", "#00aa00", parentProjectId = 0)
        runCurrent()

        assertEquals("Garden", rig.projects.creates.single().title)
        assertEquals(0L, rig.projects.creates.single().parentProjectId)
        assertEquals(listOf("Project created"), rig.shown)
        rig.close()
    }

    // --- collapsing ---

    @Test
    fun `a collapsed project hides its children until it is opened again`() = runTest {
        val rig = rig(listOf(project(1), project(10, parent = 1), project(2)))
        assertEquals(listOf(1L, 10L, 2L), rig.rowIds)

        rig.viewModel.toggleProjectCollapsed(1)
        runCurrent()
        assertEquals(listOf(1L, 2L), rig.rowIds)
        assertEquals(setOf(1L), rig.state.collapsedProjectIds)

        rig.viewModel.toggleProjectCollapsed(1)
        runCurrent()
        assertEquals(listOf(1L, 10L, 2L), rig.rowIds)
        rig.close()
    }

    // --- custom lists ---

    @Test
    fun `a dragged list shows its new place at once and is saved by index`() = runTest {
        val rig = rig(lists = listOf(customList("a"), customList("b"), customList("c")))

        rig.viewModel.reorderCustomList(movedId = "a", idsInNewOrder = listOf("b", "c", "a"))
        runCurrent()

        assertEquals(listOf(0 to 2), rig.lists.reorders)
        assertEquals(listOf("b", "c", "a"), rig.state.customLists.map { it.id })
        rig.close()
    }

    @Test
    fun `a list dropped where it was is not saved`() = runTest {
        val rig = rig(lists = listOf(customList("a"), customList("b")))

        rig.viewModel.reorderCustomList(movedId = "a", idsInNewOrder = listOf("a", "b"))
        runCurrent()

        assertTrue(rig.lists.reorders.isEmpty())
        rig.close()
    }

    @Test
    fun `a list order that cannot be stored is reported`() = runTest {
        val rig = rig(lists = listOf(customList("a"), customList("b")))
        rig.lists.failReorder = true

        rig.viewModel.reorderCustomList(movedId = "a", idsInNewOrder = listOf("b", "a"))
        runCurrent()

        assertEquals(listOf("Could not save the new order"), rig.shown)
        assertEquals(listOf("a", "b"), rig.state.customLists.map { it.id })
        rig.close()
    }

    // --- labels ---

    @Test
    fun `a dragged label is stored in the label order`() = runTest {
        val rig = rig(labels = listOf(Label(id = 1, title = "a"), Label(id = 2, title = "b"), Label(id = 3, title = "c")))
        assertEquals(listOf(1L, 2L, 3L), rig.state.labels.map { it.id })

        rig.viewModel.reorderLabel(movedId = 3, idsInNewOrder = listOf(3, 1, 2))
        runCurrent()

        assertEquals(listOf(3L, 1L, 2L), rig.labelOrder.getOrder().first())
        assertEquals(listOf(3L, 1L, 2L), rig.state.labels.map { it.id })
        rig.close()
    }

    @Test
    fun `a label dropped where it was is not stored`() = runTest {
        val rig = rig(labels = listOf(Label(id = 1, title = "a"), Label(id = 2, title = "b")))

        rig.viewModel.reorderLabel(movedId = 1, idsInNewOrder = listOf(1, 2))
        runCurrent()

        assertEquals(emptyList(), rig.labelOrder.getOrder().first())
        rig.close()
    }
}

package com.rendyhd.vicu.ui.screens.inbox

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.fakeScreenRefresher
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.POSITION_STEP
import com.rendyhd.vicu.util.PositionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** The Inbox in the order of its list view, and dragging tasks into a new one. */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelOrderTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val inbox = Project(id = 5, title = "Inbox")

    private class Rig(projects: List<Project>) {
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository(projects)
        val labels = FakeLabelRepository()
        val storage = InMemoryTokenStorage()
        val scope = CoroutineScope(SupervisorJob())
        val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = storage,
            apiServiceProvider = { error("no network in this test") },
            appScope = scope,
            networkMonitor = FakeNetworkMonitor(),
        )

        fun viewModel() = InboxViewModel(tasks, this.projects, labels, auth, fakeScreenRefresher(tasks, this.projects, labels))
    }

    private suspend fun rig(vararg tasks: Task): Rig =
        Rig(listOf(inbox)).also { rig ->
            rig.storage.storeInboxProjectId(5)
            tasks.forEach(rig.tasks::put)
        }

    private fun task(id: Long, position: Double, due: String = "") =
        Task(id = id, title = "T$id", projectId = 5, position = position, dueDate = due)

    private fun InboxViewModel.ids() = uiState.value.tasks.map { it.id }

    // --- the order ---

    @Test
    fun `tasks are listed in position order, dated ones first`() = runTest {
        val rig = rig(
            task(1, 300.0),
            task(2, 100.0),
            task(3, 200.0),
            task(4, 900.0, due = "2026-10-08T21:59:59Z"),
            task(5, 50.0, due = "2026-10-07T21:59:59Z"),
        )
        val vm = rig.viewModel()
        runCurrent()

        assertEquals(listOf(5L, 4L, 2L, 3L, 1L), vm.ids())
        rig.scope.cancel()
    }

    @Test
    fun `a stored change of position reorders the list`() = runTest {
        val rig = rig(task(1, 100.0), task(2, 200.0))
        val vm = rig.viewModel()
        runCurrent()

        rig.tasks.put(task(1, 300.0))
        runCurrent()

        assertEquals(listOf(2L, 1L), vm.ids())
        rig.scope.cancel()
    }

    // --- dragging ---

    @Test
    fun `a drag moves the row at once`() = runTest {
        val rig = rig(task(1, 100.0), task(2, 200.0), task(3, 300.0))
        val vm = rig.viewModel()
        runCurrent()

        assertTrue(vm.onTaskMoved(fromId = 3, toId = 1))

        assertEquals(listOf(3L, 1L, 2L), vm.ids())
        rig.scope.cancel()
    }

    @Test
    fun `a dated task cannot be dragged, nor dropped onto`() = runTest {
        val rig = rig(task(1, 100.0, due = "2026-10-08T21:59:59Z"), task(2, 200.0), task(3, 300.0))
        val vm = rig.viewModel()
        runCurrent()

        assertFalse(vm.onTaskMoved(fromId = 1, toId = 2))
        assertFalse(vm.onTaskMoved(fromId = 3, toId = 1))
        assertEquals(listOf(1L, 2L, 3L), vm.ids())
        rig.scope.cancel()
    }

    @Test
    fun `dropping sends the position between the new neighbours to the inbox list`() = runTest {
        val rig = rig(task(1, 100.0), task(2, 200.0), task(3, 300.0))
        val vm = rig.viewModel()
        runCurrent()
        vm.onTaskMoved(fromId = 3, toId = 2)

        vm.onTaskDropped(3)
        runCurrent()

        assertEquals(listOf(5L to listOf(PositionUpdate(3, 150.0))), rig.tasks.appliedPositions)
        assertEquals(listOf(1L, 3L, 2L), vm.ids(), "and the stored order agrees with what was dragged")
        rig.scope.cancel()
    }

    @Test
    fun `tasks that share a position are renumbered when one is dropped between them`() = runTest {
        val rig = rig(task(1, 0.0), task(2, 0.0), task(3, 0.0))
        val vm = rig.viewModel()
        runCurrent()
        vm.onTaskMoved(fromId = 3, toId = 2)

        vm.onTaskDropped(3)
        runCurrent()

        val (projectId, updates) = rig.tasks.appliedPositions.single()
        assertEquals(5L, projectId)
        assertEquals(
            listOf(PositionUpdate(1, POSITION_STEP), PositionUpdate(2, 3 * POSITION_STEP), PositionUpdate(3, 2 * POSITION_STEP)),
            updates,
            "the others first, the dragged task last",
        )
        assertEquals(listOf(1L, 3L, 2L), vm.ids())
        rig.scope.cancel()
    }

    @Test
    fun `a reorder the server refuses is reported and the server's order is read back`() = runTest {
        val rig = rig(task(1, 100.0), task(2, 200.0))
        rig.tasks.positionResult = NetworkResult.Error("Could not save the new order")
        val vm = rig.viewModel()
        runCurrent()
        rig.tasks.positionRefreshes.clear()
        vm.onTaskMoved(fromId = 2, toId = 1)

        vm.onTaskDropped(2)
        runCurrent()

        assertEquals("Could not save the new order", vm.uiState.value.error)
        assertEquals(listOf(5L), rig.tasks.positionRefreshes, "the list is put back as the server holds it")
        rig.scope.cancel()
    }

    @Test
    fun `dropping a task that is not in the list does nothing`() = runTest {
        val rig = rig(task(1, 100.0))
        val vm = rig.viewModel()
        runCurrent()

        vm.onTaskDropped(99)
        runCurrent()

        assertTrue(rig.tasks.appliedPositions.isEmpty())
        assertNull(vm.uiState.value.error)
        rig.scope.cancel()
    }

    // --- learning the order from the server ---

    @Test
    fun `tasks without a known position make the inbox ask the server for the order`() = runTest {
        val rig = rig(task(1, 0.0), task(2, 0.0))
        rig.tasks.listPositions = mapOf(1L to 200.0, 2L to 100.0)
        val vm = rig.viewModel()
        runCurrent()

        assertEquals(listOf(5L), rig.tasks.positionRefreshes)
        assertEquals(listOf(2L, 1L), vm.ids(), "and the list follows what it was told")
        rig.scope.cancel()
    }

    @Test
    fun `a task that arrives later is asked about once`() = runTest {
        val rig = rig(task(1, 100.0))
        val vm = rig.viewModel()
        runCurrent()
        assertTrue(rig.tasks.positionRefreshes.isEmpty(), "every position is known")

        rig.tasks.put(task(2, 0.0))
        runCurrent()
        rig.tasks.put(task(2, 0.0).copy(title = "Renamed"))
        runCurrent()

        assertEquals(listOf(5L), rig.tasks.positionRefreshes)
        assertEquals(2, vm.ids().size)
        rig.scope.cancel()
    }

    @Test
    fun `pulling down always reads the order again`() = runTest {
        val rig = rig(task(1, 100.0))
        val vm = rig.viewModel()
        runCurrent()

        vm.refresh(showSpinner = true)
        runCurrent()

        assertEquals(listOf(5L), rig.tasks.positionRefreshes)
        rig.scope.cancel()
    }
}

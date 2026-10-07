package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FakePlatformFiles
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

/** Completing the open task from its own screen, and keeping its title on one line. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelDoneTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private class Rig {
        val tasks = FakeTaskRepository()
        private val authScope = CoroutineScope(SupervisorJob())
        private val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = InMemoryTokenStorage(),
            apiServiceProvider = { error("no network in this test") },
            appScope = authScope,
            networkMonitor = FakeNetworkMonitor(),
        )

        fun close() = authScope.cancel()

        fun viewModel() = TaskDetailViewModel(
            taskRepository = tasks,
            labelRepository = FakeLabelRepository(),
            attachmentRepository = FakeAttachmentRepository(),
            projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"))),
            authManager = auth,
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            platformFiles = FakePlatformFiles(),
            appMessages = AppMessages(),
            dayClock = DayClock(authScope, ticking = false),
        )
    }

    private suspend fun TestScope.opened(
        task: Task = Task(id = 42, title = "Buy milk", projectId = 1),
    ): Pair<Rig, TaskDetailViewModel> {
        val rig = Rig()
        rig.tasks.put(task)
        val vm = rig.viewModel()
        vm.loadTask(task.id)
        runCurrent()
        assertEquals(task.title, vm.uiState.value.task?.title)
        return rig to vm
    }

    private val openChild = Task(id = 43, title = "Child", projectId = 1)

    // --- completing ---

    @Test
    fun `completing the open task stores it as done and the screen shows it`() = runTest {
        val (rig, vm) = opened()

        vm.requestToggleDone()
        runCurrent()

        assertEquals(listOf(42L to true), rig.tasks.setDoneCalls)
        assertTrue(vm.uiState.value.task!!.done)
        assertFalse(vm.uiState.value.showCompleteConfirmation)
        rig.close()
    }

    @Test
    fun `reopening a done task needs no question`() = runTest {
        val done = Task(
            id = 42,
            title = "Buy milk",
            projectId = 1,
            done = true,
            relatedTasks = mapOf("subtask" to listOf(openChild)),
        )
        val (rig, vm) = opened(done)

        vm.requestToggleDone()
        runCurrent()

        assertEquals(listOf(42L to false), rig.tasks.setDoneCalls)
        assertFalse(vm.uiState.value.task!!.done)
        rig.close()
    }

    @Test
    fun `a task with open subtasks asks before completing them all`() = runTest {
        val parent = Task(id = 42, title = "Buy milk", projectId = 1, relatedTasks = mapOf("subtask" to listOf(openChild)))
        val (rig, vm) = opened(parent)

        vm.requestToggleDone()
        runCurrent()
        assertTrue(vm.uiState.value.showCompleteConfirmation)
        assertTrue(rig.tasks.setDoneCalls.isEmpty())

        vm.confirmCompletion()
        runCurrent()

        assertFalse(vm.uiState.value.showCompleteConfirmation)
        assertEquals(listOf(42L to true), rig.tasks.setDoneCalls)
        rig.close()
    }

    @Test
    fun `declining the question leaves the task open`() = runTest {
        val parent = Task(id = 42, title = "Buy milk", projectId = 1, relatedTasks = mapOf("subtask" to listOf(openChild)))
        val (rig, vm) = opened(parent)

        vm.requestToggleDone()
        runCurrent()
        vm.dismissCompletion()
        runCurrent()

        assertFalse(vm.uiState.value.showCompleteConfirmation)
        assertTrue(rig.tasks.setDoneCalls.isEmpty())
        assertFalse(vm.uiState.value.task!!.done)
        rig.close()
    }

    @Test
    fun `a refused completion leaves the task open and says why`() = runTest {
        val (rig, vm) = opened()
        rig.tasks.completionOutcome = { NetworkResult.Error("Server said no") }

        vm.requestToggleDone()
        runCurrent()

        assertFalse(vm.uiState.value.task!!.done)
        assertEquals("Server said no", vm.uiState.value.error)
        rig.close()
    }

    @Test
    fun `typed edits are saved before the completion goes out`() = runTest {
        val (rig, vm) = opened()
        var updatesWhenCompleted = -1
        rig.tasks.completionOutcome = {
            updatesWhenCompleted = rig.tasks.updates.size
            null
        }

        vm.updateTitle("Buy oat milk")
        vm.requestToggleDone()
        advanceUntilIdle()

        assertEquals(1, updatesWhenCompleted, "the title was already saved")
        assertEquals("Buy oat milk", rig.tasks.current(42)!!.title)
        assertTrue(rig.tasks.current(42)!!.done)
        rig.close()
    }

    @Test
    fun `a later save keeps the task completed`() = runTest {
        val (rig, vm) = opened()

        vm.requestToggleDone()
        runCurrent()
        vm.updateTitle("Buy oat milk")
        advanceUntilIdle()

        val update = rig.tasks.updates.single()
        assertEquals("Buy oat milk", update.title)
        assertTrue(update.done, "the edit is saved on top of the completed task")
        assertTrue(rig.tasks.current(42)!!.done)
        rig.close()
    }

    @Test
    fun `completing without edits sends no update`() = runTest {
        val (rig, vm) = opened()

        vm.requestToggleDone()
        advanceUntilIdle()
        vm.saveIfChanged()
        advanceUntilIdle()

        assertTrue(rig.tasks.updates.isEmpty())
        rig.close()
    }

    // --- single-line title ---

    @Test
    fun `a title never holds a line break`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("Buy\nmilk")
        assertEquals("Buy milk", vm.uiState.value.task!!.title)
        vm.updateTitle("Buy\r\nmilk\n")
        assertEquals("Buy milk ", vm.uiState.value.task!!.title)
        assertNull(vm.uiState.value.error)
        rig.close()
    }
}

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
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The task editor saves on its own, never lets a late save touch a task opened afterwards, keeps
 * its unsaved edits across process death, and reports failures even when nobody is looking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelAutosaveTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val noFiles = object : PlatformFiles {
        override fun getFileNameAndBytes(uriString: String): Pair<String, ByteArray>? = null
        override fun getDisplayName(uriString: String): String? = null
    }

    private class Rig(scope: TestScope) {
        val tasks = FakeTaskRepository()
        val labels = FakeLabelRepository()
        val projects = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"), Project(id = 2, title = "Work")))
        val messages = AppMessages()
        val received = mutableListOf<String>()
        private val authScope = CoroutineScope(SupervisorJob())

        init {
            scope.backgroundScope.launch { messages.messages.collect { received += it.text } }
        }

        fun close() = authScope.cancel()

        val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = InMemoryTokenStorage(),
            apiServiceProvider = { error("no network in this test") },
            appScope = authScope,
            networkMonitor = FakeNetworkMonitor(),
        )

        fun viewModel(files: PlatformFiles) = TaskDetailViewModel(
            taskRepository = tasks,
            labelRepository = labels,
            attachmentRepository = FakeAttachmentRepository(),
            projectRepository = projects,
            authManager = auth,
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            platformFiles = files,
            appMessages = messages,
        )
    }

    private fun task(id: Long, title: String, projectId: Long = 1, priority: Int = 0) =
        Task(id = id, title = title, projectId = projectId, priority = priority)

    /** A rig with task 42 loaded in a view model. */
    private suspend fun TestScope.opened(
        configure: (Rig) -> Unit = {},
    ): Pair<Rig, TaskDetailViewModel> {
        val rig = Rig(this)
        rig.tasks.put(task(42, "Buy milk"))
        configure(rig)
        val vm = rig.viewModel(noFiles)
        vm.loadTask(42)
        runCurrent()
        assertEquals("Buy milk", vm.uiState.value.task?.title)
        return rig to vm
    }

    // --- debounce ---

    @Test
    fun `an edit is saved once the editor has been idle`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("Buy oat milk")
        advanceTimeBy(TaskDetailViewModel.AUTOSAVE_DELAY_MS - 1)
        runCurrent()
        assertEquals(0, rig.tasks.updates.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Buy oat milk"), rig.tasks.updates.map { it.title })
        rig.close()
    }

    @Test
    fun `every new edit restarts the wait`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("Buy oat")
        advanceTimeBy(600)
        vm.updateTitle("Buy oat milk")
        advanceTimeBy(TaskDetailViewModel.AUTOSAVE_DELAY_MS - 1)
        runCurrent()
        assertEquals(0, rig.tasks.updates.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Buy oat milk"), rig.tasks.updates.map { it.title })
        rig.close()
    }

    @Test
    fun `nothing is saved when nothing changed`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("Buy milk")
        advanceTimeBy(TaskDetailViewModel.AUTOSAVE_DELAY_MS * 3)
        runCurrent()
        vm.saveIfChanged()
        runCurrent()

        assertEquals(0, rig.tasks.updates.size)
        rig.close()
    }

    @Test
    fun `the stop save runs immediately and cancels the pending autosave`() = runTest {
        val (rig, vm) = opened()

        vm.setPriority(3)
        vm.saveIfChanged()
        runCurrent()
        assertEquals(1, rig.tasks.updates.size)

        advanceTimeBy(TaskDetailViewModel.AUTOSAVE_DELAY_MS * 2)
        runCurrent()
        assertEquals(1, rig.tasks.updates.size)
        rig.close()
    }

    // --- shortcuts and blank titles ---

    @Test
    fun `autosave leaves a title with shortcut words for the final save`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("Buy milk tomorrow")
        advanceTimeBy(TaskDetailViewModel.AUTOSAVE_DELAY_MS)
        runCurrent()
        assertEquals(0, rig.tasks.updates.size, "a half-typed shortcut must not be applied while typing")

        vm.saveIfChanged()
        runCurrent()

        val saved = rig.tasks.updates.single()
        assertEquals("Buy milk", saved.title)
        assertTrue(saved.dueDate.isNotBlank(), "the date word becomes the due date")
        rig.close()
    }

    @Test
    fun `autosave saves other fields while the title still has shortcut words`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("Buy milk @frequent")
        vm.setPriority(4)
        advanceTimeBy(TaskDetailViewModel.AUTOSAVE_DELAY_MS)
        runCurrent()

        val saved = rig.tasks.updates.single()
        assertEquals("Buy milk", saved.title, "the stored title is kept until the final save")
        assertEquals(4, saved.priority)
        assertTrue(rig.labels.created.isEmpty(), "no label is created from a half-typed @word")
        rig.close()
    }

    @Test
    fun `a title that is only shortcut words is not saved`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("tomorrow")
        vm.saveIfChanged()
        runCurrent()

        assertEquals(0, rig.tasks.updates.size)
        assertEquals("Buy milk", vm.uiState.value.task?.title, "the previous title is back in the editor")
        assertEquals(1, rig.received.size)
        assertTrue(rig.received.single().contains("previous title was kept"))
        rig.close()
    }

    @Test
    fun `an empty title keeps the previous one but still saves other changes`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("")
        vm.setPriority(2)
        vm.saveIfChanged()
        runCurrent()

        val saved = rig.tasks.updates.single()
        assertEquals("Buy milk", saved.title)
        assertEquals(2, saved.priority)
        assertTrue(rig.received.single().contains("needs a title"))
        rig.close()
    }

    @Test
    fun `autosave does not touch an empty title while the user is typing`() = runTest {
        val (rig, vm) = opened()

        vm.updateTitle("")
        advanceTimeBy(TaskDetailViewModel.AUTOSAVE_DELAY_MS)
        runCurrent()

        assertEquals("", vm.uiState.value.task?.title)
        assertEquals(0, rig.tasks.updates.size)
        assertTrue(rig.received.isEmpty())
        rig.close()
    }

    // --- late saves ---

    @Test
    fun `a save that finishes after another task was opened does not replace it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (rig, vm) = opened { it.tasks.updateHandler = { saved -> gate.await(); NetworkResult.Success(saved) } }
        rig.tasks.put(task(43, "Other task"))

        vm.setPriority(3)
        vm.saveIfChanged()
        runCurrent()
        assertEquals(1, rig.tasks.updates.size)

        vm.loadTask(43)
        runCurrent()
        assertEquals("Other task", vm.uiState.value.task?.title)

        gate.complete(Unit)
        runCurrent()

        val state = vm.uiState.value
        assertEquals(43L, state.requestedTaskId)
        assertEquals(43L, state.task?.id)
        assertEquals("Other task", state.task?.title)
        assertEquals(43L, state.originalTask?.id)
        rig.close()
    }

    @Test
    fun `edits typed while a save is in flight survive its completion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (rig, vm) = opened { it.tasks.updateHandler = { saved -> gate.await(); NetworkResult.Success(saved) } }

        vm.setPriority(3)
        vm.saveIfChanged()
        runCurrent()
        vm.updateTitle("Buy milk now")

        gate.complete(Unit)
        runCurrent()

        val state = vm.uiState.value
        assertEquals("Buy milk now", state.task?.title)
        assertEquals(3, state.task?.priority)
        assertEquals(3, state.originalTask?.priority)
        rig.close()
    }

    @Test
    fun `opening another task first saves the edits of the one being left`() = runTest {
        val (rig, vm) = opened()
        rig.tasks.put(task(43, "Other task"))

        vm.setPriority(4)
        vm.loadTask(43)
        runCurrent()

        assertEquals(42L, rig.tasks.updates.single().id)
        assertEquals(4, rig.tasks.updates.single().priority)
        assertEquals(43L, vm.uiState.value.task?.id)
        rig.close()
    }

    // --- failures ---

    @Test
    fun `a failed save is announced and the edits stay in the editor`() = runTest {
        val (rig, vm) = opened { it.tasks.updateHandler = { delay(10); NetworkResult.Error("server said no") } }

        vm.updateTitle("Buy oat milk")
        vm.saveIfChanged()
        advanceTimeBy(20)
        runCurrent()

        assertEquals(1, rig.received.size)
        assertTrue(rig.received.single().contains("server said no"))
        assertEquals("Buy oat milk", vm.uiState.value.task?.title, "the rollback must not eat the edit")
        assertEquals("Buy milk", vm.uiState.value.originalTask?.title)
        rig.close()
    }

    @Test
    fun `a failed save after another task was opened is still announced`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (rig, vm) = opened {
            it.tasks.updateHandler = { gate.await(); NetworkResult.Error("offline rejected") }
        }
        rig.tasks.put(task(43, "Other task"))

        vm.setPriority(3)
        vm.saveIfChanged()
        runCurrent()
        vm.loadTask(43)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(rig.received.single().contains("offline rejected"))
        assertEquals("Other task", vm.uiState.value.task?.title)
        rig.close()
    }

    // --- project moves ---

    @Test
    fun `moving a task moves its whole subtree and reports a partial failure`() = runTest {
        val (rig, vm) = opened {
            it.tasks.moveDescendantsResult = NetworkResult.Error("The task was moved, but 1 of 3 subtasks could not be")
        }

        vm.setProject(2)
        vm.saveIfChanged()
        runCurrent()

        assertEquals(listOf(42L to 2L), rig.tasks.moveDescendantsCalls)
        assertTrue(rig.received.single().contains("1 of 3 subtasks"))
        rig.close()
    }

    @Test
    fun `a save without a project change does not touch subtasks`() = runTest {
        val (rig, vm) = opened()

        vm.setPriority(2)
        vm.saveIfChanged()
        runCurrent()

        assertTrue(rig.tasks.moveDescendantsCalls.isEmpty())
        rig.close()
    }

    // --- deleting ---

    @Test
    fun `a task being deleted is not saved back`() = runTest {
        val (rig, vm) = opened()

        vm.setPriority(2)
        vm.deleteTask()
        runCurrent()
        vm.saveIfChanged()
        advanceTimeBy(TaskDetailViewModel.AUTOSAVE_DELAY_MS * 2)
        runCurrent()

        assertEquals(listOf(42L), rig.tasks.deleted)
        assertEquals(0, rig.tasks.updates.size)
        rig.close()
    }

    // --- draft ---

    @Test
    fun `unsaved edits form a draft that a new view model restores`() = runTest {
        val (rig, vm) = opened()
        vm.updateTitle("Buy oat milk")
        vm.setPriority(3)

        val encoded = vm.currentDraftJson()
        assertNotNull(encoded)

        // The process died: a fresh view model, and meanwhile the task changed on the server.
        rig.tasks.put(task(42, "Buy milk", priority = 0).copy(dueDate = "2026-10-09T21:59:59Z"))
        val fresh = rig.viewModel(noFiles)
        fresh.restoreDraft(encoded)
        fresh.loadTask(42)
        runCurrent()

        val restored = fresh.uiState.value
        assertEquals("Buy oat milk", restored.task?.title)
        assertEquals(3, restored.task?.priority)
        assertEquals("2026-10-09T21:59:59Z", restored.task?.dueDate, "fields the user did not touch follow the server")
        assertEquals("Buy milk", restored.originalTask?.title)
        assertTrue(TokenType.PRIORITY in restored.manuallyEditedTypes)
        rig.close()
    }

    @Test
    fun `there is no draft once the changes are saved`() = runTest {
        val (rig, vm) = opened()
        vm.setPriority(3)
        assertNotNull(vm.currentDraftJson())

        vm.saveIfChanged()
        runCurrent()

        assertNull(vm.currentDraftJson())
        rig.close()
    }

    @Test
    fun `a draft for another task is ignored and a used view model ignores a restore`() = runTest {
        val (rig, vm) = opened()
        vm.setPriority(3)
        val encoded = vm.currentDraftJson()

        rig.tasks.put(task(43, "Other task"))
        val fresh = rig.viewModel(noFiles)
        fresh.restoreDraft(encoded)
        fresh.loadTask(43)
        runCurrent()
        assertEquals(0, fresh.uiState.value.task?.priority)

        // A view model that already loaded something (rotation) keeps its own state.
        vm.restoreDraft(null)
        vm.restoreDraft(encoded)
        vm.loadTask(42)
        runCurrent()
        assertNotEquals(null, vm.uiState.value.task)
        rig.close()
    }
}

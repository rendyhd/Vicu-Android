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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The editor hands the repository the notes its draft started from (description format, "Notes
 * changed in two places"), and no longer asks which version to keep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelNotesBaseTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val tasks = FakeTaskRepository()
    private val authScope = CoroutineScope(SupervisorJob())

    private fun viewModel() = TaskDetailViewModel(
        taskRepository = tasks,
        labelRepository = FakeLabelRepository(),
        attachmentRepository = FakeAttachmentRepository(),
        projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"))),
        authManager = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = InMemoryTokenStorage(),
            apiServiceProvider = { error("no network in this test") },
            appScope = authScope,
            networkMonitor = FakeNetworkMonitor(),
        ),
        behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
        nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
        platformFiles = FakePlatformFiles(),
        appMessages = AppMessages(),
        dayClock = DayClock(authScope, ticking = false),
    )

    private val stored = Task(id = 42, title = "Plan trip", projectId = 1, description = "<p>A</p>")

    @Test
    fun `a save passes the notes the draft started from`() = runTest {
        tasks.put(stored)
        val vm = viewModel()
        vm.loadTask(42)
        runCurrent()

        vm.updateDescription("<p>B</p>")
        vm.saveIfChanged()
        runCurrent()

        assertEquals(listOf<String?>("<p>A</p>"), tasks.notesBases)
        authScope.cancel()
    }

    @Test
    fun `notes changed elsewhere while the draft has unsaved notes keep the old base and ask nothing`() = runTest {
        tasks.put(stored)
        val vm = viewModel()
        vm.loadTask(42)
        runCurrent()
        vm.updateDescription("<p>B</p>")

        tasks.put(stored.copy(description = "<p>C</p>"))
        runCurrent()
        assertEquals("<p>B</p>", vm.uiState.value.task?.description, "the draft stays")
        assertEquals(null, vm.uiState.value.error)

        vm.saveIfChanged()
        runCurrent()

        assertEquals(listOf<String?>("<p>A</p>"), tasks.notesBases)
        authScope.cancel()
    }

    @Test
    fun `without unsaved notes the base follows the stored notes`() = runTest {
        tasks.put(stored)
        val vm = viewModel()
        vm.loadTask(42)
        runCurrent()

        tasks.put(stored.copy(description = "<p>C</p>"))
        runCurrent()
        vm.updateDescription("<p>D</p>")
        vm.saveIfChanged()
        runCurrent()

        assertEquals(listOf<String?>("<p>C</p>"), tasks.notesBases)
        authScope.cancel()
    }

    @Test
    fun `after a merged save the editor shows the merged notes and they are the next base`() = runTest {
        tasks.put(stored)
        val merged = "<p>C</p><hr><p>B</p>"
        tasks.updateHandler = { NetworkResult.Success(it.copy(description = merged)) }
        val vm = viewModel()
        vm.loadTask(42)
        runCurrent()

        vm.updateDescription("<p>B</p>")
        vm.saveIfChanged()
        runCurrent()
        assertEquals(merged, vm.uiState.value.task?.description)

        tasks.updateHandler = { NetworkResult.Success(it) }
        vm.updateDescription("$merged<p>E</p>")
        vm.saveIfChanged()
        runCurrent()

        assertEquals(listOf<String?>("<p>A</p>", merged), tasks.notesBases)
        authScope.cancel()
    }
}

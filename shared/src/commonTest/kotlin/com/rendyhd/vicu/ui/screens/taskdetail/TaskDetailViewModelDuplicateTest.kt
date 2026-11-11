package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.repository.TaskDuplicator
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeCollaborationRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Duplicate in the editor's menu (issue 97): the editor shows the copy, and Undo takes it back. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelDuplicateTest {

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
        val messages = AppMessages()
        private val scope = CoroutineScope(SupervisorJob())
        private val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = InMemoryTokenStorage(),
            apiServiceProvider = { error("no network in this test") },
            appScope = scope,
            networkMonitor = FakeNetworkMonitor(),
        )
        private val labels = FakeLabelRepository()

        fun close() = scope.cancel()

        fun viewModel() = TaskDetailViewModel(
            taskRepository = tasks,
            labelRepository = labels,
            attachmentRepository = FakeAttachmentRepository(),
            projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"))),
            authManager = auth,
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            platformFiles = FakePlatformFiles(),
            appMessages = messages,
            dayClock = DayClock(scope, ticking = false),
            taskDuplicator = TaskDuplicator(tasks, labels, FakeCollaborationRepository()),
        )
    }

    @Test
    fun `undo deletes the copy and goes back to the original`() = runTest {
        val rig = Rig()
        rig.tasks.put(Task(id = 42, title = "Call the plumber", projectId = 1))
        rig.tasks.createHandler = { NetworkResult.Success(it.copy(id = 100).also { copy -> rig.tasks.put(copy) }) }
        val vm = rig.viewModel()
        vm.loadTask(42)
        runCurrent()

        vm.duplicateTask()
        advanceUntilIdle()
        assertEquals(100L, vm.uiState.value.task?.id)

        val message = rig.messages.messages.first()
        assertEquals("Duplicated. This is the copy.", message.text)
        assertEquals("Undo", message.actionLabel)
        assertNull(message.openTaskId)
        assertNotNull(message.onAction).invoke()
        advanceUntilIdle()

        assertEquals(listOf(100L), rig.tasks.deleted)
        assertEquals(42L, vm.uiState.value.task?.id)
        rig.close()
    }
}

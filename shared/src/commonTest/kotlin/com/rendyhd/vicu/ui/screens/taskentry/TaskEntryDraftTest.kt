package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.location.FakeLocationPermission
import com.rendyhd.vicu.location.testLocations
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessage
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Clearing the draft by hand, its Undo, and what the sheet says once a task is added. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEntryDraftTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val format = DateDisplayFormat("en-US", hour12 = false)
    private val messages = AppMessages()
    private val tasks = FakeTaskRepository().apply { createHandler = { NetworkResult.Success(it.copy(id = 500)) } }
    private val behavior = BehaviorPrefsStore(InMemoryPreferencesDataStore())

    private fun TestScope.viewModel(): TaskEntryViewModel {
        val vm = TaskEntryViewModel(
            taskRepository = tasks,
            projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"), Project(id = 2, title = "Work"))),
            labelRepository = FakeLabelRepository(),
            attachmentRepository = FakeAttachmentRepository(),
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = backgroundScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            notificationPrefsStore = NotificationPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = behavior,
            appMessages = messages,
            locations = testLocations(),
            locationPermission = FakeLocationPermission(),
            dayClock = DayClock(
                backgroundScope,
                FixedTimeSource(Instant.parse("2026-11-04T10:00:00Z"), TimeZone.UTC),
                ticking = false,
            ),
        )
        runCurrent()
        vm.initWithDefaults(2, null)
        runCurrent()
        return vm
    }

    @Test
    fun `an empty draft has nothing to clear`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.hasDraft)

        vm.clearDraft()

        assertFalse(vm.uiState.value.canUndoClear)
    }

    @Test
    fun `clearing takes everything away and undo puts it back`() = runTest {
        val vm = viewModel()
        vm.setTitle("Call mom")
        vm.setDescription("About the weekend")
        vm.addSubtask("Find the number")
        vm.setPriority(3)
        runCurrent()
        assertTrue(vm.uiState.value.hasDraft)

        vm.clearDraft()
        runCurrent()

        vm.uiState.value.let {
            assertEquals("", it.title)
            assertEquals("", it.description)
            assertEquals(emptyList(), it.subtasks)
            assertEquals(0, it.priority)
            assertFalse(it.hasDraft)
            assertTrue(it.canUndoClear)
        }

        vm.undoClearDraft()
        runCurrent()

        vm.uiState.value.let {
            assertEquals("Call mom", it.title)
            assertEquals("About the weekend", it.description)
            assertEquals(listOf("Find the number"), it.subtasks)
            assertEquals(3, it.priority)
            assertFalse(it.canUndoClear)
        }
    }

    @Test
    fun `typing after clearing ends the undo`() = runTest {
        val vm = viewModel()
        vm.setTitle("Call mom")
        vm.clearDraft()

        vm.setTitle("B")
        runCurrent()

        assertFalse(vm.uiState.value.canUndoClear)
        vm.undoClearDraft()
        assertEquals("B", vm.uiState.value.title)
    }

    @Test
    fun `closing the sheet after clearing ends the undo`() = runTest {
        val vm = viewModel()
        vm.setTitle("Call mom")
        vm.clearDraft()

        vm.reset()

        assertFalse(vm.uiState.value.canUndoClear)
        vm.undoClearDraft()
        assertEquals("", vm.uiState.value.title)
    }

    @Test
    fun `a saved task is confirmed with Open and Undo`() = runTest {
        val vm = viewModel()
        val said = mutableListOf<AppMessage>()
        backgroundScope.launch { messages.messages.collect { said += it } }
        vm.setTitle("Call mom")

        vm.save(format)
        runCurrent()

        val message = said.single()
        val created = assertNotNull(vm.uiState.value.savedTaskId)
        assertEquals("Added “Call mom” to Work", message.text)
        assertEquals("Undo", message.actionLabel)
        assertEquals(created, message.openTaskId)

        message.onAction?.invoke()
        runCurrent()
        assertTrue(created in tasks.deleted, "Undo removes the task again")
    }

    @Test
    fun `mass add stays quiet`() = runTest {
        behavior.setKeepEntryOpen(true)
        val vm = viewModel()
        val said = mutableListOf<AppMessage>()
        backgroundScope.launch { messages.messages.collect { said += it } }
        vm.setTitle("Call mom")

        vm.save(format)
        runCurrent()

        assertNotNull(vm.uiState.value.savedTaskId)
        assertEquals(emptyList(), said)
    }
}

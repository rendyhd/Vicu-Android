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
import com.rendyhd.vicu.domain.model.SharedContent
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Issue 104: images and files added in quick add while offline wait for the server, and the snackbar says so. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEntryOfflineFilesTest {

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
    private val attachments = FakeAttachmentRepository().apply { offline = true }

    private fun TestScope.viewModel(): TaskEntryViewModel {
        val vm = TaskEntryViewModel(
            taskRepository = tasks,
            projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"))),
            labelRepository = FakeLabelRepository(),
            attachmentRepository = attachments,
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = backgroundScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            notificationPrefsStore = NotificationPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            appMessages = messages,
            locations = testLocations(),
            locationPermission = FakeLocationPermission(),
            dayClock = DayClock(
                backgroundScope,
                FixedTimeSource(Instant.parse("2026-11-10T10:00:00Z"), TimeZone.UTC),
                ticking = false,
            ),
        )
        runCurrent()
        vm.initWithDefaults(1, null)
        runCurrent()
        return vm
    }

    @Test
    fun `a pasted image waits for the server and its placeholder never reaches the notes`() = runTest {
        val said = mutableListOf<AppMessage>()
        backgroundScope.launch { messages.messages.collect { said += it } }
        val vm = viewModel()
        vm.setTitle("Fix the porch light")
        vm.setDescription("Breaker trips")
        vm.stagePendingImage("content://pick/IMG_2031.jpg")

        vm.save(format)
        runCurrent()

        assertEquals(listOf(Triple(500L, "content://pick/IMG_2031.jpg", true)), attachments.queued)
        assertTrue(attachments.uploads.isEmpty())
        val notes = tasks.updates.last().description
        assertEquals("Breaker trips", notes)
        assertFalse(notes.contains("image-pending"))
        val confirmation = said.single()
        assertTrue(confirmation.text.endsWith(". 1 file uploads when you're back online."), confirmation.text)
        assertEquals("Undo", confirmation.actionLabel)
    }

    @Test
    fun `shared files wait for the server too`() = runTest {
        val said = mutableListOf<AppMessage>()
        backgroundScope.launch { messages.messages.collect { said += it } }
        val vm = viewModel()
        vm.initWithSharedContent(1, SharedContent(text = "Quote", fileUris = listOf("content://pick/quote.pdf", "content://pick/plan.pdf")))
        runCurrent()
        vm.setTitle("Quote")

        vm.save(format)
        runCurrent()

        assertEquals(listOf(500L, 500L), attachments.queued.map { it.first })
        assertTrue(said.single().text.endsWith(". 2 files upload when you're back online."), said.single().text)
    }
}

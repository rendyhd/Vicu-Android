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
import com.rendyhd.vicu.ui.screens.shared.SEARCH_REFRESH_DEBOUNCE_MS
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FakePlatformFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The relation picker in the task editor reads the cache first and asks the server in the
 * background: after the text has rested, one request at a time, the one in flight cancelled by a
 * new text, and never a request per keystroke (A-UI-16, UI-23).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailRelationSearchTest {

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

        val viewModel = TaskDetailViewModel(
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

        fun close() = authScope.cancel()
    }

    /** A rig whose result list is being collected, the way the screen does. */
    private fun TestScope.rig(): Rig = Rig().also { rig ->
        backgroundScope.launch { rig.viewModel.relationSearchResults.collect { } }
        runCurrent()
    }

    @Test
    fun `typing asks the server once, after the text has rested`() = runTest {
        val rig = rig()

        listOf("m", "mi", "mil", "milk").forEach {
            rig.viewModel.setRelationSearchQuery(it)
            advanceTimeBy(60)
        }
        assertEquals(emptyList(), rig.tasks.refreshes, "no request per keystroke")

        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)

        assertEquals(listOf(mapOf("q" to "milk")), rig.tasks.refreshes)
        rig.close()
    }

    @Test
    fun `a new text cancels the request that is in flight`() = runTest {
        val rig = rig()
        rig.tasks.refreshGate = CompletableDeferred()

        rig.viewModel.setRelationSearchQuery("ab")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        rig.viewModel.setRelationSearchQuery("abc")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        rig.tasks.refreshGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf(mapOf("q" to "ab"), mapOf("q" to "abc")), rig.tasks.refreshes)
        assertEquals(1, rig.tasks.completedRefreshes, "only the last text's request ran to the end")
        rig.close()
    }

    @Test
    fun `the results come from the cache, open and completed, without waiting for the server`() = runTest {
        val rig = rig()
        rig.tasks.put(Task(id = 10, title = "Buy milk", projectId = 1))
        rig.tasks.put(Task(id = 11, title = "Milk run", projectId = 1, done = true))
        rig.tasks.put(Task(id = 12, title = "Walk the dog", projectId = 1))
        rig.tasks.refreshGate = CompletableDeferred()

        rig.viewModel.setRelationSearchQuery("milk")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)

        assertEquals(setOf(10L, 11L), rig.viewModel.relationSearchResults.value.map { it.id }.toSet())
        assertEquals(0, rig.tasks.completedRefreshes, "the server has not answered yet")
        rig.close()
    }

    @Test
    fun `a blank text shows nothing and asks nothing`() = runTest {
        val rig = rig()
        rig.tasks.put(Task(id = 10, title = "Buy milk", projectId = 1))

        rig.viewModel.setRelationSearchQuery("milk")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        rig.tasks.refreshes.clear()
        rig.viewModel.setRelationSearchQuery("   ")
        advanceTimeBy(1_000)

        assertEquals(emptyList(), rig.viewModel.relationSearchResults.value)
        assertEquals(emptyList(), rig.tasks.refreshes)
        rig.close()
    }
}

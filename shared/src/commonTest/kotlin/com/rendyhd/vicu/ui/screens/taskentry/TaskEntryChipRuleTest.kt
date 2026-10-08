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
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chip rule of the new-task sheet (the same as the desktop composer, card 3.5): a chip shows
 * what the parser read from the title unless the user set that chip, in which case the chip wins,
 * its token loses the highlight (the parser stops reading it) and what is saved is what the chips
 * showed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEntryChipRuleTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val zone = TimeZone.currentSystemDefault()
    private val today = Clock.System.todayIn(zone)
    private val tomorrowDue = DueDates.tomorrow(today, zone).toString()
    private val inboxId = 1L
    private val personalId = 2L
    private val workId = 3L

    private class Rig(val tasks: FakeTaskRepository, val vm: TaskEntryViewModel, val authScope: CoroutineScope)

    private fun TestScope.rig(): Rig {
        val tasks = FakeTaskRepository().apply {
            createHandler = { NetworkResult.Success(it.copy(id = 500)) }
        }
        val authScope = CoroutineScope(SupervisorJob())
        val vm = TaskEntryViewModel(
            taskRepository = tasks,
            projectRepository = FakeProjectRepository(
                listOf(Project(id = 1, title = "Inbox"), Project(id = 2, title = "Personal"), Project(id = 3, title = "Work")),
            ),
            labelRepository = FakeLabelRepository(),
            attachmentRepository = FakeAttachmentRepository(),
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            notificationPrefsStore = NotificationPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            appMessages = AppMessages(),
            dayClock = DayClock(backgroundScope, FixedTimeSource(Clock.System.now(), zone), ticking = false),
        )
        runCurrent()
        vm.initWithDefaults(defaultProjectId = inboxId, defaultDueDate = null)
        runCurrent()
        return Rig(tasks, vm, authScope)
    }

    private fun TestScope.saved(rig: Rig): Task {
        rig.vm.save()
        runCurrent()
        val task = rig.tasks.created.single()
        rig.authScope.cancel()
        return task
    }

    private fun Rig.fields() = resolveEntryFields(vm.uiState.value, zone)

    private fun Rig.tokenTypes() = vm.uiState.value.parseResult?.tokens?.map { it.type }.orEmpty()

    // --- Without a chip set, the text is read --------------------------------------------------

    @Test
    fun `the text fills the chips and is saved`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana tomorrow #Personal !high")

        val fields = rig.fields()
        assertEquals(tomorrowDue, fields.dueDate)
        assertEquals(FieldSource.TEXT, fields.dueSource)
        assertEquals(3, fields.priority)
        assertEquals(FieldSource.TEXT, fields.prioritySource)
        assertEquals(personalId, fields.projectId)
        assertEquals(FieldSource.TEXT, fields.projectSource)

        val task = saved(rig)
        assertEquals("Call Ana", task.title)
        assertEquals(tomorrowDue, task.dueDate)
        assertEquals(3, task.priority)
        assertEquals(personalId, task.projectId)
    }

    @Test
    fun `a project that does not exist is flagged and the task keeps the selected one`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana #Nowhere ")

        val fields = rig.fields()
        assertTrue(fields.projectNotFound)
        assertEquals("Nowhere", fields.parsedProjectName)
        assertEquals(inboxId, fields.projectId)
        assertEquals(inboxId, saved(rig).projectId)
    }

    // --- A chip that was set wins, and its token loses the highlight ---------------------------

    @Test
    fun `a picked priority wins over the text and its token is no longer highlighted`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana !high")
        assertTrue(TokenType.PRIORITY in rig.tokenTypes())

        rig.vm.setPriority(1)

        assertFalse(TokenType.PRIORITY in rig.tokenTypes(), "the typed priority is plain text now")
        val fields = rig.fields()
        assertEquals(1, fields.priority)
        assertEquals(FieldSource.CHIP, fields.prioritySource)
        val task = saved(rig)
        assertEquals(1, task.priority)
        assertEquals("Call Ana !high", task.title, "the unread words stay in the title")
    }

    @Test
    fun `picking None for the priority wins over the text`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana !high")
        rig.vm.setPriority(0)

        assertEquals(0, rig.fields().priority)
        assertEquals(0, saved(rig).priority)
    }

    @Test
    fun `a picked project wins over the one in the text`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana #Personal")
        rig.vm.setProjectId(workId)

        assertFalse(TokenType.PROJECT in rig.tokenTypes())
        val fields = rig.fields()
        assertEquals(workId, fields.projectId)
        assertEquals(FieldSource.CHIP, fields.projectSource)
        assertFalse(fields.projectNotFound)
        assertEquals(workId, saved(rig).projectId)
    }

    @Test
    fun `a picked date wins over the text and its words stay in the title`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana tomorrow")
        assertTrue(TokenType.DATE in rig.tokenTypes())
        val picked = DueDates.pickDate(today.plus(10, DateTimeUnit.DAY), zone).toString()

        rig.vm.setDueDate(picked)

        assertFalse(TokenType.DATE in rig.tokenTypes())
        assertEquals(FieldSource.CHIP, rig.fields().dueSource)
        val task = saved(rig)
        assertEquals(picked, task.dueDate)
        assertEquals("Call Ana tomorrow", task.title)
    }

    @Test
    fun `a repeat picked as None wins over a repeat in the text`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Water the plants every week")
        assertEquals(FieldSource.TEXT, rig.fields().recurrenceSource)

        rig.vm.setRecurrence(RecurrenceValue.NONE)

        assertFalse(TokenType.RECURRENCE in rig.tokenTypes())
        assertEquals(null, rig.fields().recurrenceSource)
        assertEquals(0L, saved(rig).repeatAfter)
    }

    @Test
    fun `a chip stays set while the title is edited and the text is not read again`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana !high")
        rig.vm.setPriority(2)

        rig.vm.setTitle("Call Ana !high now")
        rig.vm.setTitle("Call Ana !urgent now")

        assertFalse(TokenType.PRIORITY in rig.tokenTypes())
        assertEquals(2, rig.fields().priority)
    }

    @Test
    fun `a chip that was not set still follows the text`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana !high #Personal")
        rig.vm.setPriority(1) // only the priority is pinned

        assertTrue(TokenType.PROJECT in rig.tokenTypes())
        assertEquals(personalId, rig.fields().projectId)
        rig.vm.setTitle("Call Ana !high #Work")
        assertEquals(workId, rig.fields().projectId)
    }

    @Test
    fun `clearing the labels pins them and a fresh draft reads the text again`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana @errand")
        assertTrue(TokenType.LABEL in rig.tokenTypes())
        rig.vm.clearLabels()
        assertFalse(TokenType.LABEL in rig.tokenTypes())

        rig.vm.reset()
        rig.vm.setTitle("Call Ana @errand !high")
        assertTrue(TokenType.LABEL in rig.tokenTypes())
        assertTrue(TokenType.PRIORITY in rig.tokenTypes())
        rig.authScope.cancel()
    }

    @Test
    fun `a dismissed chip still lifts when its words are deleted, a pinned one does not`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call Ana !high")
        rig.vm.suppressType(TokenType.PRIORITY)
        rig.vm.setTitle("Call Ana")
        rig.vm.setTitle("Call Ana !low")
        assertTrue(TokenType.PRIORITY in rig.tokenTypes(), "dismissing is lifted once the words are gone")

        rig.vm.setPriority(3)
        rig.vm.setTitle("Call Ana")
        rig.vm.setTitle("Call Ana !low")
        assertFalse(TokenType.PRIORITY in rig.tokenTypes(), "a chip that was set is not lifted by editing")
        rig.authScope.cancel()
    }
}

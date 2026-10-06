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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which due date a new task gets: a date seeded by the screen (Today's FAB) counts as unset and a
 * date typed in the title beats it, while a date picked by hand beats the title.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEntryViewModelDueDateTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val zone = TimeZone.currentSystemDefault()

    /** The parser reads "today" from the system clock, so the day clock is frozen at the real day. */
    private val today = Clock.System.todayIn(zone)
    private val todayDue = DueDates.today(today, zone).toString()
    private val tomorrowDue = DueDates.tomorrow(today, zone).toString()

    private class Rig(
        val tasks: FakeTaskRepository,
        val nlp: NlpPrefsStore,
        val behavior: BehaviorPrefsStore,
        val vm: TaskEntryViewModel,
        val authScope: CoroutineScope,
    )

    private fun TestScope.rig(): Rig {
        val tasks = FakeTaskRepository().apply {
            createHandler = { NetworkResult.Success(it.copy(id = 500)) }
        }
        val nlp = NlpPrefsStore(InMemoryPreferencesDataStore())
        val behavior = BehaviorPrefsStore(InMemoryPreferencesDataStore())
        val authScope = CoroutineScope(SupervisorJob())
        val vm = TaskEntryViewModel(
            taskRepository = tasks,
            projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"))),
            labelRepository = FakeLabelRepository(),
            attachmentRepository = FakeAttachmentRepository(),
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            nlpPrefsStore = nlp,
            notificationPrefsStore = NotificationPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = behavior,
            appMessages = AppMessages(),
            dayClock = DayClock(
                backgroundScope,
                FixedTimeSource(Clock.System.now(), zone),
                ticking = false,
            ),
        )
        runCurrent()
        return Rig(tasks, nlp, behavior, vm, authScope)
    }

    private fun TestScope.saved(rig: Rig): Task {
        rig.vm.save()
        runCurrent()
        val task = rig.tasks.created.single()
        rig.authScope.cancel()
        return task
    }

    @Test
    fun `a date typed in the title beats the date seeded by the Today screen`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = todayDue)
        runCurrent()
        rig.vm.setTitle("Buy milk tomorrow")

        val task = saved(rig)

        assertEquals("Buy milk", task.title)
        assertEquals(tomorrowDue, task.dueDate)
    }

    @Test
    fun `the seeded date is used when the title has no date`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = todayDue)
        runCurrent()
        rig.vm.setTitle("Buy milk")

        val task = saved(rig)

        assertEquals("Buy milk", task.title)
        assertEquals(todayDue, task.dueDate)
    }

    @Test
    fun `saving a task downloads nothing else`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = null)
        runCurrent()
        rig.vm.setTitle("Buy milk")

        saved(rig)

        assertTrue(rig.tasks.refreshes.isEmpty(), "the create stored the server's answer; no refresh follows")
    }

    @Test
    fun `a date picked by hand beats a date typed in the title`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = todayDue)
        runCurrent()
        val picked = DueDates.pickDate(today.plus(10, DateTimeUnit.DAY), zone).toString()
        rig.vm.setDueDate(picked)
        rig.vm.setTitle("Buy milk tomorrow")

        val task = saved(rig)

        assertEquals(picked, task.dueDate)
    }

    @Test
    fun `a typed time is kept`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = null)
        runCurrent()
        rig.vm.setTitle("Call tomorrow 3pm")

        val task = saved(rig)

        assertEquals(today.plus(1, DateTimeUnit.DAY), DueDates.localDateOf(task.dueDate, zone))
        assertEquals("15:00", Instant.parse(task.dueDate).toLocalDateTime(zone).time.toString())
    }

    @Test
    fun `a typed date without a time is date-only`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = null)
        runCurrent()
        rig.vm.setTitle("Call next week")

        val task = saved(rig)

        assertTrue(DueDates.isDateOnly(task.dueDate, zone))
        assertEquals(DueDates.nextWeekStart(today), DueDates.localDateOf(task.dueDate, zone))
    }

    @Test
    fun `the bang shortcut from the Today screen strips the bang and keeps today`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = todayDue)
        runCurrent()
        rig.vm.setTitle("Buy milk !")

        val task = saved(rig)

        assertEquals("Buy milk", task.title)
        assertEquals(todayDue, task.dueDate)
    }

    @Test
    fun `with the parser off the bang is today date-only`() = runTest {
        val rig = rig()
        rig.nlp.setEnabled(false)
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = null)
        runCurrent()
        rig.vm.setTitle("Buy milk !")

        val task = saved(rig)

        assertEquals("Buy milk", task.title)
        assertEquals(todayDue, task.dueDate)
        assertTrue(DueDates.isDateOnly(task.dueDate, zone))
    }

    @Test
    fun `with the parser off the bang beats the Today seed too and is stripped`() = runTest {
        val rig = rig()
        rig.nlp.setEnabled(false)
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = todayDue)
        runCurrent()
        rig.vm.setTitle("Buy milk !")

        val task = saved(rig)

        assertEquals("Buy milk", task.title)
        assertEquals(todayDue, task.dueDate)
    }

    @Test
    fun `clearing the date by hand leaves a typed date in charge`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = todayDue)
        runCurrent()
        rig.vm.clearDueDate()
        rig.vm.setTitle("Buy milk tomorrow")

        val task = saved(rig)

        assertEquals(tomorrowDue, task.dueDate)
    }

    @Test
    fun `reset restores the seeded date for mass-add and drops a picked one`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = todayDue)
        runCurrent()
        rig.vm.setDueDate(DueDates.pickDate(today.plus(5, DateTimeUnit.DAY), zone).toString())

        rig.vm.reset()

        // Back to the seed, and the seed counts as unset again: a typed date beats it.
        assertEquals(todayDue, rig.vm.uiState.value.dueDate)
        rig.vm.setTitle("Next one tomorrow")
        val task = saved(rig)
        assertEquals(tomorrowDue, task.dueDate)
    }

    @Test
    fun `no seed and no date leaves the task without a due date`() = runTest {
        val rig = rig()
        rig.vm.initWithDefaults(defaultProjectId = 1, defaultDueDate = null)
        runCurrent()
        rig.vm.setTitle("Buy milk")

        val task = saved(rig)

        assertEquals("", task.dueDate)
    }
}

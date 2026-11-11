package com.rendyhd.vicu.ui.screens.plan

import com.rendyhd.vicu.calendar.CalendarPrefsStore
import com.rendyhd.vicu.calendar.NoDeviceCalendars
import com.rendyhd.vicu.calendar.PlanCalendars
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.repository.InMemorySyncedEntries
import com.rendyhd.vicu.data.repository.PlannerPreferences
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.ProjectAccess
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.repository.RoutineParseIssue
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.ui.FakeCollaborationRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.fakeScreenRefresher
import com.rendyhd.vicu.util.AppMessage
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.Permission
import com.rendyhd.vicu.util.PlanSource
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.EventRepeat
import com.rendyhd.vicu.util.EventScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the Plan screen writes (contract 16.2, "What each action writes") and what it refuses. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val zone = TimeZone.of("Pacific/Auckland")
    private val today = LocalDate(2026, 10, 6)
    private val now = LocalDateTime(2026, 10, 6, 8, 0).toInstant(zone)

    private fun at(local: String): String = LocalDateTime.parse(local).toInstant(zone).toString()
    private fun dateOnly(date: LocalDate): String = DueDates.dateOnlyDue(date, zone).toString()

    private class NoRoutines : RoutineRepository {
        override fun observeActive(): Flow<List<Routine>> = flowOf(emptyList())
        override fun observeArchived(): Flow<List<Routine>> = flowOf(emptyList())
        override fun observeRoutine(routineId: String): Flow<Routine?> = flowOf(null)
        override fun observeDay(date: String): Flow<RoutineDay> = flowOf(RoutineDay(date, emptyList()))
        override fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>> = flowOf(emptyList())
        override fun observeIssues(): Flow<List<RoutineParseIssue>> = flowOf(emptyList())
        override suspend fun create(draft: RoutineDraft): NetworkResult<Routine> = NetworkResult.Error("not faked")
        override suspend fun update(routineId: String, draft: RoutineDraft): NetworkResult<Routine> =
            NetworkResult.Error("not faked")

        override suspend fun setOccurrenceStatus(
            routineId: String,
            date: String,
            slotId: String,
            status: OccurrenceStatus,
            note: String,
        ): NetworkResult<Routine> = NetworkResult.Error("not faked")

        override suspend fun archive(routineId: String, archived: Boolean): NetworkResult<Routine> =
            NetworkResult.Error("not faked")

        override suspend fun deletePermanently(routineId: String): NetworkResult<Unit> = NetworkResult.Success(Unit)
        override suspend fun finalizeAndPrune(): NetworkResult<Unit> = NetworkResult.Success(Unit)
        override suspend fun exportCsv(): String = ""
    }

    private inner class Rig(scope: TestScope, viewOnlyProject: Long? = null) {
        val tasks = FakeTaskRepository()
        val messages = AppMessages()
        val behavior = BehaviorPrefsStore(InMemoryPreferencesDataStore())
        val synced = InMemorySyncedEntries()
        val calendars = PlanCalendars(synced, NoDeviceCalendars, CalendarPrefsStore(InMemoryPreferencesDataStore()), FixedTimeSource(now, zone))
        val authScope = CoroutineScope(SupervisorJob())
        private val projects = FakeProjectRepository(
            listOf(Project(id = 1, title = "Home", position = 1.0), Project(id = 2, title = "Shared", position = 2.0)),
        )
        val vm = PlanViewModel(
            taskRepository = tasks,
            projectRepository = projects,
            routineRepository = NoRoutines(),
            routinePrefsStore = RoutinePrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = behavior,
            plannerPreferences = PlannerPreferences(synced),
            taskOrderPreferences = TaskOrderPreferences(synced),
            collaborationRepository = FakeCollaborationRepository(
                CollabState(
                    me = 1,
                    projects = viewOnlyProject?.let {
                        mapOf(it to ProjectAccess(shared = true, members = emptyList(), permission = Permission.READ))
                    }.orEmpty(),
                ),
            ),
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            refresher = fakeScreenRefresher(tasks, projects, FakeLabelRepository()),
            dayClock = DayClock(scope.backgroundScope, FixedTimeSource(now, zone), ticking = false),
            appMessages = messages,
            planCalendars = calendars,
            notificationPrefsStore = NotificationPrefsStore(InMemoryPreferencesDataStore()),
            time = FixedTimeSource(now, zone),
        )

        init {
            scope.backgroundScope.launch { vm.uiState.collect { } }
        }

        fun last(id: Long): Task = assertNotNull(tasks.current(id))
    }

    private fun open(id: Long, due: String = "", projectId: Long = 1) =
        Task(id = id, title = "Task $id", projectId = projectId, dueDate = due)

    private fun block(id: Long, start: String, end: String, projectId: Long = 1, due: String = "") = Task(
        id = id,
        title = "Task $id",
        projectId = projectId,
        startDate = at(start),
        endDate = at(end),
        dueDate = due,
    )

    @Test
    fun `placing writes the start and the default length and the due rule`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(open(1))
        runCurrent()

        rig.vm.placeAt(rig.last(1), 15 * 60)
        runCurrent()

        val written = rig.last(1)
        assertEquals(at("2026-10-06T15:00:00"), written.startDate)
        assertEquals(at("2026-10-06T15:30:00"), written.endDate)
        assertEquals(1, rig.tasks.updates.size)
        // A task without a due date gets the block's day as its date-only due date (16.2).
        assertEquals(dateOnly(today), written.dueDate)
        rig.authScope.cancel()
    }

    @Test
    fun `placing takes the synced default length and keeps a task's own`() = runTest {
        val rig = Rig(this)
        PlannerPreferences(rig.synced).setDefaultLength(45)
        rig.tasks.put(open(1))
        rig.tasks.put(block(2, "2026-10-06T09:00:00", "2026-10-06T10:00:00"))
        runCurrent()

        rig.vm.placeAt(rig.last(1), 10 * 60)
        rig.vm.placeAt(rig.last(2), 14 * 60)
        runCurrent()

        assertEquals(at("2026-10-06T10:45:00"), rig.last(1).endDate)
        assertEquals(at("2026-10-06T15:00:00"), rig.last(2).endDate)
        rig.authScope.cancel()
    }

    @Test
    fun `a past day takes no block`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(open(1))
        runCurrent()
        rig.vm.moveStrip(-1)
        runCurrent()
        assertEquals(LocalDate(2026, 9, 29), rig.vm.uiState.value.selectedDay)

        assertEquals(PlanViewModel.PAST_DAY, rig.vm.placingProblem(rig.last(1)))
        rig.vm.placeAt(rig.last(1), 15 * 60)
        runCurrent()

        assertTrue(rig.tasks.updates.isEmpty())
        assertEquals(PlanViewModel.PAST_DAY, rig.messages.messages.first().text)
        rig.authScope.cancel()
    }

    @Test
    fun `a view-only member sees a block and cannot change it`() = runTest {
        val rig = Rig(this, viewOnlyProject = 2)
        rig.tasks.put(block(1, "2026-10-06T09:00:00", "2026-10-06T10:00:00", projectId = 2))
        runCurrent()

        val state = rig.vm.uiState.value
        assertEquals(listOf(1L), state.content?.blocks?.map { it.task.id })
        assertFalse(state.canChange(rig.last(1)))

        rig.vm.move(rig.last(1), 12 * 60)
        rig.vm.resize(rig.last(1), 11 * 60)
        rig.vm.removeTime(rig.last(1))
        rig.vm.toggleDone(rig.last(1))
        runCurrent()

        assertTrue(rig.tasks.updates.isEmpty())
        assertTrue(rig.tasks.toggled.isEmpty())
        rig.authScope.cancel()
    }

    @Test
    fun `moving keeps the length and the due time follows when it was the block's own`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(block(1, "2026-10-06T09:00:00", "2026-10-06T09:45:00", due = dateOnly(today)))
        runCurrent()

        rig.vm.move(rig.last(1), 11 * 60)
        runCurrent()

        assertEquals(at("2026-10-06T11:00:00"), rig.last(1).startDate)
        assertEquals(at("2026-10-06T11:45:00"), rig.last(1).endDate)
        assertEquals(dateOnly(today), rig.last(1).dueDate)
        rig.authScope.cancel()
    }

    @Test
    fun `a block dragged to another day keeps its length and the due rule moves an earlier date`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(block(1, "2026-10-06T09:00:00", "2026-10-06T09:45:00", due = dateOnly(today)))
        runCurrent()

        rig.vm.moveTo(rig.last(1), LocalDate(2026, 10, 8), 14 * 60)
        runCurrent()

        assertEquals(at("2026-10-08T14:00:00"), rig.last(1).startDate)
        assertEquals(at("2026-10-08T14:45:00"), rig.last(1).endDate)
        assertEquals(dateOnly(LocalDate(2026, 10, 8)), rig.last(1).dueDate)
        rig.authScope.cancel()
    }

    @Test
    fun `moving to a day keeps the times and follows the block there and undo puts everything back`() = runTest {
        val rig = Rig(this)
        val messages = mutableListOf<AppMessage>()
        backgroundScope.launch { rig.messages.messages.collect { messages += it } }
        val before = block(1, "2026-10-06T09:00:00", "2026-10-06T10:15:00", due = dateOnly(today))
        rig.tasks.put(before)
        runCurrent()

        rig.vm.moveToDay(rig.last(1), LocalDate(2026, 10, 9))
        runCurrent()

        assertEquals(at("2026-10-09T09:00:00"), rig.last(1).startDate)
        assertEquals(at("2026-10-09T10:15:00"), rig.last(1).endDate)
        assertEquals(dateOnly(LocalDate(2026, 10, 9)), rig.last(1).dueDate)
        assertEquals(LocalDate(2026, 10, 9), rig.vm.uiState.value.selectedDay)
        val moved = messages.last()
        assertTrue(moved.text.startsWith("Moved to"), moved.text)

        moved.onAction!!.invoke()
        runCurrent()
        assertEquals(before.startDate, rig.last(1).startDate)
        assertEquals(before.endDate, rig.last(1).endDate)
        assertEquals(before.dueDate, rig.last(1).dueDate)
        rig.authScope.cancel()
    }

    @Test
    fun `moving to a past day is refused`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(block(1, "2026-10-06T09:00:00", "2026-10-06T09:30:00"))
        runCurrent()

        rig.vm.moveToDay(rig.last(1), LocalDate(2026, 10, 5))
        rig.vm.moveTo(rig.last(1), LocalDate(2026, 10, 5), 9 * 60)
        runCurrent()

        assertEquals(at("2026-10-06T09:00:00"), rig.last(1).startDate)
        rig.authScope.cancel()
    }

    @Test
    fun `placing a task due earlier says the due date moved with undo`() = runTest {
        val rig = Rig(this)
        val messages = mutableListOf<AppMessage>()
        backgroundScope.launch { rig.messages.messages.collect { messages += it } }
        rig.tasks.put(open(1, due = dateOnly(LocalDate(2026, 10, 1))))
        runCurrent()

        rig.vm.placeAt(rig.last(1), 10 * 60)
        runCurrent()

        assertEquals(dateOnly(today), rig.last(1).dueDate)
        val said = messages.last()
        assertTrue(said.text.startsWith("Planned for") && "Due date moved from" in said.text, said.text)

        said.onAction!!.invoke()
        runCurrent()
        assertEquals(dateOnly(LocalDate(2026, 10, 1)), rig.last(1).dueDate)
        assertTrue(TimeBlocks.blockOf(rig.last(1), zone) == null)
        rig.authScope.cancel()
    }

    @Test
    fun `a drop on the top row removes the time and keeps the day`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(block(1, "2026-10-06T09:00:00", "2026-10-06T09:30:00", due = dateOnly(LocalDate(2026, 10, 9))))
        runCurrent()

        rig.vm.planForDay(rig.last(1), today)
        runCurrent()

        assertTrue(TimeBlocks.blockOf(rig.last(1), zone) == null)
        assertEquals(dateOnly(today), rig.last(1).dueDate)
        assertEquals(listOf(1L), rig.vm.uiState.value.content?.topRow?.map { it.id })
        rig.authScope.cancel()
    }

    @Test
    fun `swiping past the strip moves it a week and the neighbours are read`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(open(1, due = dateOnly(LocalDate(2026, 10, 13))))
        rig.tasks.put(block(2, "2026-10-05T09:00:00", "2026-10-05T09:30:00"))
        runCurrent()

        assertEquals(listOf(2L), rig.vm.uiState.value.previous?.blocks?.map { it.task.id })
        repeat(7) { rig.vm.stepDay(1) }
        runCurrent()

        val state = rig.vm.uiState.value
        assertEquals(LocalDate(2026, 10, 13), state.selectedDay)
        assertTrue(state.stripMoved)
        assertEquals(LocalDate(2026, 10, 13), state.strip.first().day)
        assertEquals(listOf(1L), state.content?.topRow?.map { it.id })

        rig.vm.stepDay(-1)
        runCurrent()
        assertEquals(LocalDate(2026, 10, 12), rig.vm.uiState.value.selectedDay)
        assertEquals(LocalDate(2026, 10, 6), rig.vm.uiState.value.strip.first().day)
        rig.authScope.cancel()
    }

    @Test
    fun `the drawer chips count the inbox and the next seven days and no date`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(open(1, due = dateOnly(today)))
        rig.tasks.put(open(2, due = dateOnly(LocalDate(2026, 10, 9))))
        rig.tasks.put(open(3))
        rig.tasks.upcomingTasks.value = listOf(rig.last(2))
        runCurrent()

        val counts = rig.vm.uiState.value.sourceCounts
        assertEquals(1, counts[PlanSource.Day.key])
        assertEquals(1, counts[PlanSource.NextSeven.key])
        assertEquals(1, counts[PlanSource.NoDate.key])
        rig.authScope.cancel()
    }

    @Test
    fun `the length changes by the end only`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(block(1, "2026-10-06T09:00:00", "2026-10-06T09:30:00"))
        runCurrent()

        rig.vm.resize(rig.last(1), 10 * 60 + 15)
        runCurrent()
        assertEquals(at("2026-10-06T09:00:00"), rig.last(1).startDate)
        assertEquals(at("2026-10-06T10:15:00"), rig.last(1).endDate)

        rig.vm.setLength(rig.last(1), 15)
        runCurrent()
        assertEquals(at("2026-10-06T09:15:00"), rig.last(1).endDate)
        rig.authScope.cancel()
    }

    @Test
    fun `a nudge moves by a quarter and stays inside the day`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(block(1, "2026-10-06T00:00:00", "2026-10-06T00:30:00"))
        rig.tasks.put(block(2, "2026-10-06T09:00:00", "2026-10-06T09:30:00"))
        runCurrent()

        rig.vm.nudge(rig.last(1), -15)
        rig.vm.nudge(rig.last(2), 15)
        runCurrent()

        assertEquals(at("2026-10-06T00:00:00"), rig.last(1).startDate)
        assertEquals(at("2026-10-06T09:15:00"), rig.last(2).startDate)
        assertEquals(at("2026-10-06T09:45:00"), rig.last(2).endDate)
        rig.authScope.cancel()
    }

    @Test
    fun `remove time clears both dates and the reminders that followed them but not the due date`() = runTest {
        val rig = Rig(this)
        val due = dateOnly(today)
        rig.tasks.put(
            block(1, "2026-10-06T09:00:00", "2026-10-06T09:30:00", due = due).copy(
                reminders = listOf(
                    TaskReminder(relativePeriod = -600, relativeTo = "start_date"),
                    TaskReminder(relativePeriod = 0, relativeTo = "end_date"),
                    TaskReminder(relativePeriod = -3600, relativeTo = "due_date"),
                ),
            ),
        )
        runCurrent()

        rig.vm.removeTime(rig.last(1))
        runCurrent()

        val written = rig.last(1)
        assertEquals(TimeBlocks.CLEARED, written.startDate)
        assertEquals(TimeBlocks.CLEARED, written.endDate)
        assertEquals(due, written.dueDate)
        assertEquals(listOf(TaskReminder(relativePeriod = -3600, relativeTo = "due_date")), written.reminders)
        rig.authScope.cancel()
    }

    @Test
    fun `next free takes the first gap of the work hours`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(open(1))
        rig.tasks.put(block(2, "2026-10-06T08:00:00", "2026-10-06T10:00:00"))
        runCurrent()

        assertTrue(rig.vm.placeInNextFree(rig.last(1)))
        runCurrent()

        assertEquals(at("2026-10-06T10:00:00"), rig.last(1).startDate)
        assertEquals(at("2026-10-06T10:30:00"), rig.last(1).endDate)
        rig.authScope.cancel()
    }

    @Test
    fun `next free says so when nothing fits`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(open(1))
        rig.tasks.put(block(2, "2026-10-06T08:00:00", "2026-10-06T18:00:00"))
        runCurrent()

        assertFalse(rig.vm.placeInNextFree(rig.last(1)))
        runCurrent()

        assertTrue(rig.tasks.updates.isEmpty())
        rig.authScope.cancel()
    }

    @Test
    fun `selecting a day asks for that day once and overdue stays on today`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(open(1, due = dateOnly(today)))
        rig.tasks.put(open(2, due = dateOnly(LocalDate(2026, 10, 1))))
        rig.tasks.put(open(3, due = dateOnly(LocalDate(2026, 10, 8))))
        runCurrent()

        assertEquals(listOf(today), rig.tasks.planDays)
        assertEquals(listOf(1L, 2L), rig.vm.uiState.value.content?.withoutTime?.map { it.id }?.sorted())

        rig.vm.selectDay(LocalDate(2026, 10, 8), today)
        runCurrent()

        assertEquals(listOf(today, LocalDate(2026, 10, 8)), rig.tasks.planDays)
        assertEquals(listOf(3L), rig.vm.uiState.value.content?.withoutTime?.map { it.id })
        rig.authScope.cancel()
    }

    @Test
    fun `the drawer shows the chosen list and remembers it and filters by title`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(open(1, projectId = 2).copy(title = "Call the dentist"))
        rig.tasks.put(open(2, projectId = 2).copy(title = "Book flights"))
        rig.tasks.put(open(3, projectId = 1).copy(title = "Other project"))
        runCurrent()

        rig.vm.setSource(PlanSource.Project(2))
        runCurrent()
        assertEquals(listOf(1L, 2L), rig.vm.uiState.value.listed.map { it.id }.sorted())
        assertEquals("project:2", rig.behavior.getPrefs().first().plannerSource)

        rig.vm.setFilter("DENT")
        runCurrent()
        assertEquals(listOf(1L), rig.vm.uiState.value.listed.map { it.id })
        assertEquals(2, rig.vm.uiState.value.listedCount)

        // Another list starts without the old words.
        rig.vm.setSource(PlanSource.NoDate)
        runCurrent()
        assertEquals("", rig.vm.uiState.value.filter)
        rig.authScope.cancel()
    }

    @Test
    fun `a done block stays on the timeline and a second tick reopens it`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(block(1, "2026-10-06T09:00:00", "2026-10-06T09:30:00"))
        runCurrent()

        rig.vm.toggleDone(rig.last(1))
        runCurrent()
        assertEquals(listOf(1L), rig.tasks.toggled)
        assertEquals(listOf(1L), rig.vm.uiState.value.content?.blocks?.map { it.task.id })
        assertEquals(0, rig.vm.uiState.value.content?.plannedMinutes)

        rig.vm.toggleDone(rig.last(1))
        runCurrent()
        assertEquals(listOf(1L to false), rig.tasks.setDoneCalls)
        rig.authScope.cancel()
    }

    // Opened by a link with the planner off, Plan leaves for Today and navigation keeps its view model.
    // Coming back brings that view model back: an old "off" in it sent Plan away again on every visit.
    @Test
    fun `a view model kept while the planner was off lets Plan open once it is on`() = runTest {
        val rig = Rig(this)
        rig.behavior.setPlannerEnabled(false)
        val screen = backgroundScope.launch { rig.vm.uiState.collect { } }
        runCurrent()
        assertTrue(rig.vm.plannerOff.first(), "a link with the planner off leaves")
        screen.cancel()
        runCurrent()

        rig.behavior.setPlannerEnabled(true)
        runCurrent()

        assertFalse(rig.vm.plannerOff.first(), "the setting decides, whatever the kept state holds")
        rig.authScope.cancel()
    }

    @Test
    fun `turning the planner off with Plan open sends it away`() = runTest {
        val rig = Rig(this)
        rig.behavior.setPlannerEnabled(true)
        val answers = mutableListOf<Boolean>()
        backgroundScope.launch { rig.vm.plannerOff.collect { answers += it } }
        runCurrent()

        rig.behavior.setPlannerEnabled(false)
        runCurrent()

        assertEquals(listOf(false, true), answers)
        rig.authScope.cancel()
    }

    // --- Events (contract 20) ----------------------------------------------------------------------

    private fun eventValue(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json)

    @Test
    fun `a new Event is one synced entry and its Undo deletes it`() = runTest {
        val rig = Rig(this)
        val said = mutableListOf<AppMessage>()
        backgroundScope.launch { rig.messages.messages.collect { said += it } }
        runCurrent()

        rig.vm.createEvent("Manager call", today, 10 * 60, 30, EventRepeat.Weekly(1, listOf(2)))
        runCurrent()

        val entries = rig.synced.entries.first().filterValues { it != null }
        val (key, value) = entries.entries.single()
        assertTrue(key.startsWith("event."))
        assertEquals(
            eventValue("""{"title":"Manager call","start":"2026-10-06","time":"10:00","minutes":30,"repeat":{"kind":"weekly","every":1,"days":[2]}}"""),
            value,
        )
        val shown = rig.vm.uiState.value.content?.events.orEmpty()
        assertEquals(listOf(600 to 630), shown.map { it.startMinute to it.endMinute })

        said.last().onAction?.invoke()
        runCurrent()
        assertTrue(rig.synced.entries.first().filterValues { it != null }.isEmpty())
        rig.authScope.cancel()
    }

    @Test
    fun `this time only skips the day and makes a single Event and Undo puts both back`() = runTest {
        val rig = Rig(this)
        val stored = eventValue("""{"title":"Standup","start":"2026-10-05","time":"09:00","minutes":15,"repeat":{"kind":"daily","every":1},"color":"kept"}""")
        rig.synced.set("event.a", stored)
        val said = mutableListOf<AppMessage>()
        backgroundScope.launch { rig.messages.messages.collect { said += it } }
        runCurrent()

        val occurrence = rig.vm.uiState.value.content?.events.orEmpty().single().event
        assertEquals("a", occurrence.planEventId)
        assertTrue(rig.vm.repeats(occurrence))
        rig.vm.moveEvent(occurrence, com.rendyhd.vicu.util.GestureRange(10 * 60, 10 * 60 + 30), EventScope.THIS)
        runCurrent()

        val entries = rig.synced.entries.first().filterValues { it != null }
        assertEquals(2, entries.size)
        assertEquals(
            eventValue("""{"title":"Standup","start":"2026-10-05","time":"09:00","minutes":15,"repeat":{"kind":"daily","every":1},"skips":["2026-10-06"],"color":"kept"}"""),
            entries["event.a"],
        )
        val single = entries.filterKeys { it != "event.a" }.values.single()
        assertEquals(
            eventValue("""{"title":"Standup","start":"2026-10-06","time":"10:00","minutes":30,"color":"kept"}"""),
            single,
        )

        said.last().onAction?.invoke()
        runCurrent()
        assertEquals(mapOf("event.a" to stored), rig.synced.entries.first().filterValues { it != null })
        rig.authScope.cancel()
    }

    @Test
    fun `an Event is busy time for the next free slot`() = runTest {
        val rig = Rig(this)
        rig.synced.set("event.a", eventValue("""{"title":"Focus","start":"2026-10-06","time":"08:00","minutes":120}"""))
        rig.tasks.put(open(1))
        runCurrent()

        assertTrue(rig.vm.placeInNextFree(rig.last(1)))
        runCurrent()

        assertEquals(at("2026-10-06T10:00:00"), rig.last(1).startDate)
        rig.authScope.cancel()
    }
}

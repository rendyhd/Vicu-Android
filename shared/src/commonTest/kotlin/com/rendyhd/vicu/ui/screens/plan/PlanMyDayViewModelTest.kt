package com.rendyhd.vicu.ui.screens.plan

import com.rendyhd.vicu.ai.organize.PlanCanWait
import com.rendyhd.vicu.ai.organize.PlanDayRequest
import com.rendyhd.vicu.ai.organize.PlanDayResult
import com.rendyhd.vicu.ai.organize.PlanDaySuggester
import com.rendyhd.vicu.ai.organize.PlanLeftover
import com.rendyhd.vicu.ai.organize.PlanPick
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
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.LeftoverAction
import com.rendyhd.vicu.util.MinuteRange
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.Permission
import com.rendyhd.vicu.util.TimeBlocks
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

/** What a Plan my day session writes and when it asks (contract 14.10, ai-assistant section 10). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanMyDayViewModelTest {

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
    private val tomorrow = LocalDate(2026, 10, 7)
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

    private class FakeSuggester(var ready: Boolean = true) : PlanDaySuggester {
        val requests = mutableListOf<PlanDayRequest>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        var failure: Exception? = null
        var answer: (PlanDayRequest) -> PlanDayResult = { PlanDayResult(emptyList(), emptyList(), emptyList(), emptyList()) }

        override suspend fun isReady(): Boolean = ready

        override suspend fun suggest(request: PlanDayRequest): PlanDayResult {
            requests += request
            try {
                gate?.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
            failure?.let { throw it }
            return answer(request)
        }
    }

    private inner class Rig(scope: TestScope, ready: Boolean = false, viewOnlyProject: Long? = null) {
        val tasks = FakeTaskRepository()
        val suggester = FakeSuggester(ready)
        val messages = AppMessages()
        val said = mutableListOf<String>()
        val behavior = BehaviorPrefsStore(InMemoryPreferencesDataStore())
        val synced = InMemorySyncedEntries()
        val authScope = CoroutineScope(SupervisorJob())
        private val projects = FakeProjectRepository(
            listOf(Project(id = 1, title = "Home", position = 1.0), Project(id = 2, title = "Shared", position = 2.0)),
        )
        val vm by lazy {
            PlanMyDayViewModel(
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
                suggester = suggester,
                dayClock = DayClock(scope.backgroundScope, FixedTimeSource(now, zone), ticking = false),
                appMessages = messages,
                time = FixedTimeSource(now, zone),
            )
        }

        init {
            scope.backgroundScope.launch { messages.messages.collect { said += it.text } }
        }

        fun open(scope: TestScope) {
            scope.backgroundScope.launch { vm.uiState.collect { } }
            scope.runCurrent()
        }

        fun last(id: Long): Task = assertNotNull(tasks.current(id))
    }

    private fun task(id: Long, due: String = "", projectId: Long = 1, priority: Int = 0) =
        Task(id = id, title = "Task $id", projectId = projectId, dueDate = due, priority = priority)

    private fun noDate(value: String) = value.isBlank() || value == Constants.NULL_DATE_STRING

    private fun answerFor(
        leftovers: List<PlanLeftover> = emptyList(),
        picks: List<PlanPick> = emptyList(),
        canWait: List<PlanCanWait> = emptyList(),
    ) = PlanDayResult(leftovers, picks, canWait, emptyList())

    // --- Leftovers: written at once --------------------------------------------------------------

    @Test
    fun `a leftover sent to today gets the date without a time at once`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(
            task(1, due = dateOnly(LocalDate(2026, 10, 5))).copy(
                startDate = at("2026-10-05T10:00:00"),
                endDate = at("2026-10-05T10:30:00"),
            ),
        )
        rig.open(this)
        assertEquals(listOf(1L), rig.vm.uiState.value.leftoverRows.map { it.task.id })

        rig.vm.doLeftover(rig.last(1), LeftoverAction.TODAY)
        runCurrent()

        val written = rig.last(1)
        assertEquals(dateOnly(today), written.dueDate)
        assertTrue(noDate(written.startDate))
        assertTrue(noDate(written.endDate))
        assertEquals("Moved to today", rig.vm.uiState.value.leftoverRows.single().handled)
    }

    @Test
    fun `no date clears the date and the time`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(task(1, due = dateOnly(LocalDate(2026, 10, 5))))
        rig.open(this)

        rig.vm.doLeftover(rig.last(1), LeftoverAction.NO_DATE)
        runCurrent()

        assertTrue(noDate(rig.last(1).dueDate))
    }

    @Test
    fun `done is the completion and nothing else`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(task(1, due = dateOnly(LocalDate(2026, 10, 5))))
        rig.open(this)

        rig.vm.doLeftover(rig.last(1), LeftoverAction.DONE)
        runCurrent()

        assertEquals(listOf(1L to true), rig.tasks.setDoneCalls)
        assertTrue(rig.tasks.updates.isEmpty())
    }

    @Test
    fun `a task the person cannot change is left out of every step`() = runTest {
        val rig = Rig(this, viewOnlyProject = 2)
        rig.tasks.put(task(1, due = dateOnly(LocalDate(2026, 10, 5)), projectId = 2))
        rig.tasks.put(task(2, due = dateOnly(today), projectId = 2))
        rig.tasks.put(task(3, due = dateOnly(today), projectId = 1))
        rig.open(this)

        val state = rig.vm.uiState.value
        assertTrue(state.leftoverRows.isEmpty())
        assertEquals(listOf(3L), state.onDay.map { it.id })
    }

    // --- Choose and Arrange: session state until Accept ------------------------------------------

    @Test
    fun `what is on the day starts ticked and ticking changes the meter`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.tasks.put(task(2, due = dateOnly(tomorrow)))
        rig.open(this)

        assertEquals(setOf(1L), rig.vm.uiState.value.ticked)
        val before = rig.vm.uiState.value.meter
        assertEquals(30, before.toPlace)

        rig.vm.toggleTick(2)
        runCurrent()

        assertEquals(setOf(1L, 2L), rig.vm.uiState.value.ticked)
        assertEquals(60, rig.vm.uiState.value.meter.toPlace)
        assertTrue(rig.tasks.updates.isEmpty(), "ticking writes nothing")
    }

    @Test
    fun `a length goes round the cycle`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.open(this)

        rig.vm.cycleLength(1)
        runCurrent()
        assertEquals(45, rig.vm.uiState.value.meter.toPlace)
    }

    @Test
    fun `arrange draws a draft and writes nothing until accept then one patch per task`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(task(1, due = dateOnly(today), priority = 3))
        rig.tasks.put(task(2, due = dateOnly(today)))
        rig.open(this)

        rig.vm.setStep(STEP_ARRANGE)
        runCurrent()
        val state = rig.vm.uiState.value
        assertEquals(listOf(1L, 2L), state.draft.placed.map { it.id })
        assertEquals(setOf(1L, 2L), state.dashedIds)
        assertEquals(2, state.blocks.size, "the draft is on the timeline")
        assertTrue(rig.tasks.updates.isEmpty(), "nothing is saved yet")

        rig.vm.accept()
        runCurrent()

        assertEquals(listOf(1L, 2L), rig.tasks.updates.map { it.id })
        assertEquals(at("2026-10-06T08:00:00"), rig.last(1).startDate)
        assertEquals(at("2026-10-06T08:30:00"), rig.last(1).endDate)
        assertEquals(at("2026-10-06T08:30:00"), rig.last(2).startDate)
        assertEquals(STEP_READY, rig.vm.uiState.value.step)
    }

    @Test
    fun `a task placed by hand stays where it was put and the rest go around it`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(task(1, due = dateOnly(today), priority = 3))
        rig.tasks.put(task(2, due = dateOnly(today)))
        rig.open(this)
        rig.vm.setStep(STEP_ARRANGE)
        runCurrent()

        rig.vm.moveBlock(rig.last(2), 8 * 60)
        runCurrent()

        val draft = rig.vm.uiState.value.draft
        assertEquals(8 * 60, draft.placed.first { it.id == 2L }.start)
        assertEquals(8 * 60 + 30, draft.placed.first { it.id == 1L }.start)
        assertTrue(rig.tasks.updates.isEmpty(), "a drag on the draft writes nothing")
    }

    @Test
    fun `a kept task gets the day without a time`() = runTest {
        val rig = Rig(this)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.tasks.put(task(2, due = dateOnly(tomorrow)))
        rig.open(this)
        rig.vm.toggleTick(2)
        rig.vm.setStep(STEP_ARRANGE)
        runCurrent()

        rig.vm.toggleKeep(1)
        rig.vm.toggleKeep(2)
        runCurrent()
        assertTrue(rig.vm.uiState.value.draft.placed.isEmpty())

        rig.vm.accept()
        runCurrent()

        assertEquals(listOf(2L), rig.tasks.updates.map { it.id }, "already due on the day: nothing to write")
        assertEquals(dateOnly(today), rig.last(2).dueDate)
        assertTrue(noDate(rig.last(2).startDate))
    }

    @Test
    fun `a saved block moved during arrange is written and comes back with undo all`() = runTest {
        val rig = Rig(this)
        val saved = task(1, due = dateOnly(today)).copy(startDate = at("2026-10-06T09:00:00"), endDate = at("2026-10-06T10:00:00"))
        rig.tasks.put(saved)
        rig.open(this)
        rig.vm.setStep(STEP_ARRANGE)
        runCurrent()

        rig.vm.moveBlock(rig.last(1), 11 * 60)
        runCurrent()
        assertEquals(at("2026-10-06T11:00:00"), rig.last(1).startDate)

        rig.vm.undoAll()
        runCurrent()
        assertEquals(saved.startDate, rig.last(1).startDate)
        assertEquals(saved.endDate, rig.last(1).endDate)
    }

    // --- Ready -----------------------------------------------------------------------------------

    @Test
    fun `undo all puts back everything the session changed`() = runTest {
        val rig = Rig(this)
        val reminder = TaskReminder(reminder = at("2026-10-05T09:00:00"), relativePeriod = 0, relativeTo = "")
        val late = task(1, due = dateOnly(LocalDate(2026, 10, 5))).copy(reminders = listOf(reminder))
        val finished = task(2, due = dateOnly(LocalDate(2026, 10, 4)))
        val placed = task(3, due = dateOnly(today))
        rig.tasks.put(late)
        rig.tasks.put(finished)
        rig.tasks.put(placed)
        rig.open(this)

        rig.vm.doLeftover(rig.last(1), LeftoverAction.TOMORROW)
        rig.vm.doLeftover(rig.last(2), LeftoverAction.DONE)
        runCurrent()
        rig.vm.setStep(STEP_ARRANGE)
        runCurrent()
        rig.vm.accept()
        runCurrent()
        rig.vm.setBlockReminders(true)
        runCurrent()
        assertTrue(rig.last(3).reminders.any { it.relativeTo == "start_date" && it.relativePeriod == 0L })
        assertEquals(at("2026-10-06T08:00:00"), rig.last(3).reminders.single().reminder)
        assertTrue(rig.last(2).done)

        rig.vm.undoAll()
        runCurrent()

        assertEquals(late.dueDate, rig.last(1).dueDate)
        assertEquals(listOf(reminder), rig.last(1).reminders)
        assertFalse(rig.last(2).done)
        assertTrue(noDate(rig.last(3).startDate))
        assertTrue(rig.last(3).reminders.isEmpty())
        assertEquals(STEP_LEFTOVERS, rig.vm.uiState.value.step)
    }

    @Test
    fun `turning the block reminders off takes them away and keeps the others`() = runTest {
        val rig = Rig(this)
        val other = TaskReminder(reminder = at("2026-10-06T07:00:00"), relativePeriod = -3600, relativeTo = "due_date")
        rig.tasks.put(
            task(1, due = dateOnly(today)).copy(
                startDate = at("2026-10-06T09:00:00"),
                endDate = at("2026-10-06T10:00:00"),
                reminders = listOf(other),
            ),
        )
        rig.open(this)

        rig.vm.setBlockReminders(true)
        runCurrent()
        assertEquals(2, rig.last(1).reminders.size)

        rig.vm.setBlockReminders(false)
        runCurrent()
        assertEquals(listOf(other), rig.last(1).reminders)
    }

    @Test
    fun `the nudge is a per-device time that starts at half past nine`() = runTest {
        val rig = Rig(this)
        rig.open(this)
        assertNull(rig.vm.uiState.value.nudge)

        rig.vm.setNudge(true)
        runCurrent()
        assertEquals("21:30", rig.vm.uiState.value.nudge)

        rig.vm.setNudge(false)
        runCurrent()
        assertNull(rig.vm.uiState.value.nudge)
    }

    // --- The assistant ----------------------------------------------------------------------------

    @Test
    fun `the request waits for the server and reads the day as it then is`() = runTest {
        val rig = Rig(this, ready = true)
        // Stored from earlier: a block that fills the morning, which another device has since removed.
        rig.tasks.put(
            task(1, due = dateOnly(tomorrow)).copy(startDate = at("2026-10-07T08:00:00"), endDate = at("2026-10-07T12:00:00")),
        )
        val gate = CompletableDeferred<Unit>()
        rig.tasks.refreshGate = gate
        rig.open(this)

        rig.vm.setDay(1)
        runCurrent()
        assertTrue(rig.suggester.requests.isEmpty(), "nothing goes out before the read finished")
        assertTrue(rig.vm.uiState.value.asking)

        // What the server says arrives in the store, and only then is the request built.
        rig.tasks.put(rig.last(1).copy(startDate = "", endDate = ""))
        gate.complete(Unit)
        runCurrent()

        val sent = rig.suggester.requests.single()
        assertEquals(tomorrow, sent.day)
        assertEquals(listOf(MinuteRange(8 * 60, 18 * 60)), sent.free)
        assertEquals(tomorrow, rig.tasks.planDays.last(), "the day's own list was read too")
    }

    @Test
    fun `offline the request goes out with what is stored`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.refreshResult = NetworkResult.Error("offline", offline = true)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.open(this)

        val sent = rig.suggester.requests.single()
        assertEquals(listOf(1L), sent.candidates.map { it.id })
    }

    @Test
    fun `one request when the session opens and nothing more for ticks and steps`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.tasks.put(task(2, due = dateOnly(tomorrow)))
        rig.suggester.answer = {
            answerFor(picks = listOf(PlanPick(1, 60, "10:00", "Long"), PlanPick(2, 15, null, "Quick")))
        }
        rig.open(this)
        assertEquals(1, rig.suggester.requests.size)

        rig.vm.setStep(STEP_CHOOSE)
        rig.vm.toggleTick(2)
        runCurrent()
        rig.vm.setStep(STEP_ARRANGE)
        runCurrent()
        rig.vm.setArrangeByAssistant(false)
        runCurrent()

        assertEquals(1, rig.suggester.requests.size)
    }

    @Test
    fun `ask again sends another and a day change sends for that day`() = runTest {
        val rig = Rig(this, ready = true)
        rig.open(this)
        assertEquals(1, rig.suggester.requests.size)

        rig.vm.askAgain()
        runCurrent()
        assertEquals(2, rig.suggester.requests.size)

        rig.vm.setDay(1)
        runCurrent()
        assertEquals(3, rig.suggester.requests.size)
        assertEquals(tomorrow, rig.suggester.requests.last().day)
    }

    @Test
    fun `suggestions change lengths and starts and first free slots needs no request`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.suggester.answer = { answerFor(picks = listOf(PlanPick(1, 60, "10:00", "Long"))) }
        rig.open(this)

        var state = rig.vm.uiState.value
        assertEquals(60, state.lengths[1])
        assertTrue(1L in state.suggestedLengths)
        assertEquals(60, state.meter.toPlace)
        assertEquals(10 * 60, state.draft.placed.single().start)
        assertEquals("Long", state.draft.placed.single().reason)

        rig.vm.setArrangeByAssistant(false)
        runCurrent()
        state = rig.vm.uiState.value
        assertEquals(8 * 60, state.draft.placed.single().start)
        assertEquals(1, rig.suggester.requests.size)
    }

    @Test
    fun `a length the person changed is no longer the assistant's`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.suggester.answer = { answerFor(picks = listOf(PlanPick(1, 60, null, "Long"))) }
        rig.open(this)

        rig.vm.cycleLength(1)
        runCurrent()

        val state = rig.vm.uiState.value
        assertEquals(90, state.lengths[1])
        assertFalse(1L in state.suggestedLengths)
    }

    @Test
    fun `an answer belongs to its day and a day change stops what is on its way`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.tasks.put(task(2, due = dateOnly(tomorrow)))
        val gate = CompletableDeferred<Unit>()
        rig.suggester.gate = gate
        rig.suggester.answer = { request ->
            answerFor(picks = listOf(PlanPick(if (request.day == today) 1 else 2, 60, null, "for ${request.day}")))
        }
        rig.open(this)
        assertEquals(today, rig.suggester.requests.single().day)

        rig.vm.setDay(1)
        runCurrent()
        assertEquals(1, rig.suggester.cancelled, "what was on its way stopped")

        gate.complete(Unit)
        runCurrent()
        var state = rig.vm.uiState.value
        assertEquals(tomorrow, state.day)
        assertEquals(setOf(2L), state.picks.keys)

        // Back on today the answer for tomorrow does not show; today is asked again.
        rig.suggester.gate = CompletableDeferred()
        rig.vm.setDay(0)
        runCurrent()
        state = rig.vm.uiState.value
        assertTrue(state.picks.isEmpty())
        assertTrue(state.asking)
    }

    @Test
    fun `a failure is said once and the session goes on with first free slots`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.suggester.failure = IllegalStateException("down")
        rig.open(this)

        val state = rig.vm.uiState.value
        assertEquals("No suggestions: down", state.failure)
        assertEquals(listOf("No suggestions: down"), rig.said)
        assertFalse(state.asking)
        assertEquals(8 * 60, state.draft.placed.single().start)
        assertEquals(30, state.lengths[1])

        rig.vm.setStep(STEP_CHOOSE)
        rig.vm.toggleTick(1)
        runCurrent()
        assertEquals(1, rig.said.size, "said once")
        assertEquals(1, rig.suggester.requests.size)

        rig.suggester.failure = null
        rig.vm.askAgain()
        runCurrent()
        assertEquals(2, rig.suggester.requests.size)
        assertNull(rig.vm.uiState.value.failure)
    }

    @Test
    fun `with the switch off nothing is sent and nothing is shown`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.suggester.answer = { answerFor(picks = listOf(PlanPick(1, 60, "10:00", "Long"))) }
        rig.open(this)
        rig.vm.setAssistantOn(false)
        runCurrent()

        val state = rig.vm.uiState.value
        assertTrue(state.picks.isEmpty())
        assertEquals(30, state.lengths[1])
        assertEquals(8 * 60, state.draft.placed.single().start)

        // This day was asked already: on again shows the answer in hand and does not ask twice.
        rig.vm.setAssistantOn(true)
        runCurrent()
        assertEquals(1, rig.suggester.requests.size)
        assertEquals(60, rig.vm.uiState.value.lengths[1])

        rig.vm.setAssistantOn(false)
        runCurrent()
        rig.vm.setDay(1)
        runCurrent()
        assertEquals(1, rig.suggester.requests.size, "off: a day change sends nothing")
    }

    @Test
    fun `without ask set up there is no switch and no request`() = runTest {
        val rig = Rig(this, ready = false)
        rig.open(this)
        assertFalse(rig.vm.uiState.value.assistantReady)
        assertTrue(rig.suggester.requests.isEmpty())
    }

    @Test
    fun `apply the suggestions writes each leftover`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(LocalDate(2026, 10, 5))))
        rig.tasks.put(task(2, due = dateOnly(LocalDate(2026, 10, 4))))
        rig.suggester.answer = {
            answerFor(
                leftovers = listOf(
                    PlanLeftover(1, LeftoverAction.TOMORROW, "Fits tomorrow"),
                    PlanLeftover(2, LeftoverAction.NO_DATE, "No real deadline"),
                ),
            )
        }
        rig.open(this)
        assertEquals(2, rig.vm.uiState.value.suggestedLeftovers.size)
        assertEquals("Fits tomorrow", rig.vm.uiState.value.leftoverRows.first { it.task.id == 1L }.reason)

        rig.vm.applySuggestions()
        runCurrent()

        assertEquals(dateOnly(tomorrow), rig.last(1).dueDate)
        assertTrue(noDate(rig.last(2).dueDate))
        assertTrue(rig.vm.uiState.value.suggestedLeftovers.isEmpty())
    }

    @Test
    fun `a pick not ticked is suggested and what can wait moves to the next day`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(today)))
        rig.tasks.put(task(2, due = dateOnly(tomorrow)))
        rig.suggester.answer = {
            answerFor(picks = listOf(PlanPick(2, 15, null, "Quick")), canWait = listOf(PlanCanWait(1, "Lowest priority")))
        }
        rig.open(this)

        var state = rig.vm.uiState.value
        assertEquals(listOf(2L), state.suggestedAdds.map { it.first.id })
        assertFalse(2L in state.ticked, "a suggestion is not ticked until the person ticks it")
        assertEquals(listOf(1L), state.canWait.map { it.first.id })

        rig.vm.moveToNextDay(rig.last(1))
        runCurrent()
        state = rig.vm.uiState.value
        assertEquals(dateOnly(tomorrow), rig.last(1).dueDate)
        assertFalse(1L in state.ticked)
    }

    @Test
    fun `the request has the leftovers and the groups in order and no routine names`() = runTest {
        val rig = Rig(this, ready = true)
        rig.tasks.put(task(1, due = dateOnly(LocalDate(2026, 10, 5))))
        rig.tasks.put(task(2, due = dateOnly(today)))
        rig.tasks.put(task(3, due = dateOnly(tomorrow)))
        rig.open(this)

        val sent = rig.suggester.requests.single()
        assertEquals(listOf(1L), sent.leftovers.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), sent.candidates.map { it.id }, "overdue is on today, then the day, then coming up")
        assertEquals(8 * 60, sent.nowMinute)
        assertEquals(TimeBlocks.DEFAULT_LENGTH_MINUTES, sent.defaultLength)
        assertTrue(sent.routineMinutes.isEmpty())
    }

    @Test
    fun `a session kept while the planner was off stays open once it is on`() = runTest {
        val rig = Rig(this)
        rig.behavior.setPlannerEnabled(false)
        assertTrue(rig.vm.plannerOff.first(), "the planner off closes the session")

        rig.behavior.setPlannerEnabled(true)
        runCurrent()

        assertFalse(rig.vm.plannerOff.first(), "the setting decides, whatever the kept state holds")
    }

    @Test
    fun `turning the planner off closes the session`() = runTest {
        val rig = Rig(this)
        rig.behavior.setPlannerEnabled(true)
        val answers = mutableListOf<Boolean>()
        backgroundScope.launch { rig.vm.plannerOff.collect { answers += it } }
        runCurrent()

        rig.behavior.setPlannerEnabled(false)
        runCurrent()

        assertEquals(listOf(false, true), answers)
    }
}

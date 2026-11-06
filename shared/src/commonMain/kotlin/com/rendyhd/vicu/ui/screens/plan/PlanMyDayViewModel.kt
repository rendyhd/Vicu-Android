package com.rendyhd.vicu.ui.screens.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.ai.organize.PlanDaySuggester
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.PlannerPreferences
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.BlockChanges
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.LeftoverAction
import com.rendyhd.vicu.util.MinuteRange
import com.rendyhd.vicu.util.PlanDay
import com.rendyhd.vicu.util.PlanSessionLog
import com.rendyhd.vicu.util.PlannerNudge
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.postIfRefused
import com.rendyhd.vicu.util.sortProjectTasks
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * One Plan my day session (docs/cross-app-semantics-v1.md section 14.10): the choices, the writes and
 * the log Undo all reads. The rules are in [PlanDay]. Leftovers are written at once; Choose and Arrange
 * are this view model's state until Accept writes one patch per task.
 *
 * The assistant (docs/ai-assistant-v1.md section 10) sends one request when the session opens, again
 * when its day changes or on Ask again. A change of what is ticked lays the draft out again from the
 * answer in hand and sends nothing. The request is built from what the server just said, not from
 * what happens to be in Room: a block another device removed would still count as busy, and the start
 * the model then suggested would be dropped without a word.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanMyDayViewModel(
    private val taskRepository: TaskRepository,
    projectRepository: ProjectRepository,
    routineRepository: RoutineRepository,
    routinePrefsStore: RoutinePrefsStore,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    plannerPreferences: PlannerPreferences,
    taskOrderPreferences: TaskOrderPreferences,
    collaborationRepository: CollaborationRepository,
    authManager: AuthManager,
    private val refresher: ScreenRefresher,
    private val suggester: PlanDaySuggester,
    private val dayClock: DayClock,
    private val appMessages: AppMessages,
    private val time: TimeSource = SystemTimeSource,
) : ViewModel() {

    private val choices = MutableStateFlow(PlanChoices())
    private val answers = MutableStateFlow(PlanAnswers())
    private val log = PlanSessionLog()

    /** Writes leave one at a time, in the order they were asked for. */
    private val writing = Mutex()

    private val dayOffset = choices.map { it.dayOffset }.distinctUntilChanged()
    private val day: Flow<LocalDate> = combine(dayClock.today, dayOffset) { today, offset -> today.plus(offset, DateTimeUnit.DAY) }
        .distinctUntilChanged()

    private val projects = projectRepository.getAll()
    private val taskOrders = taskOrderPreferences.settings

    /** A project's open tasks in its order, each a row of its own only when it is not nested. */
    private fun projectList(id: Long?): Flow<List<Task>> {
        if (id == null) return flowOf(emptyList())
        return combine(taskRepository.getByProjectId(id), taskOrders, projects) { tasks, orders, all ->
            sortProjectTasks(tasks.filter { !it.done }, orders.orderFor(id, all))
                .withoutNestedSubtasks(hideChildrenOfCompletedParents = false)
        }
    }

    private val dayList: Flow<Pair<LocalDate, List<Task>>> = day.flatMapLatest { d ->
        taskRepository.getPlanTasks(d, d).map { d to it }
    }
    private val inboxList = authManager.inboxProjectId.distinctUntilChanged().flatMapLatest { id ->
        projectList(id).map { id to it }
    }
    private val pickedList = choices.map { it.projectId }.distinctUntilChanged().flatMapLatest(::projectList)

    private val routines: Flow<List<TimelineRoutine>> = combine(
        day,
        routinePrefsStore.enabled,
        behaviorPrefsStore.getPrefs().map { it.plannerShowRoutines }.distinctUntilChanged(),
    ) { d, enabled, shown -> d.takeIf { enabled && shown }?.toString() to d.toString() }
        .distinctUntilChanged()
        .flatMapLatest { (date, d) ->
            if (date != null) routineRepository.observeDay(date) else flowOf(RoutineDay(d, emptyList()))
        }
        .map(::routinesOnTimeline)

    private data class Settings(val hours: com.rendyhd.vicu.util.PlannerHours, val length: Int, val nudge: String?)

    private val settings = combine(
        plannerPreferences.hours,
        plannerPreferences.defaultLength,
        behaviorPrefsStore.getPrefs(),
    ) { hours, length, prefs -> Settings(hours, length, prefs.plannerNudge) }

    /**
     * The planner is off: the session has no business being open. Read from the setting, not kept in
     * [uiState], where navigation could bring back an old answer (see PlanViewModel.plannerOff).
     */
    val plannerOff: Flow<Boolean> = behaviorPrefsStore.getPrefs().map { !it.plannerEnabled }.distinctUntilChanged()

    private val sharing = combine(projects, collaborationRepository.state, dayClock.day) { all, collab, clock ->
        Triple(all, collab, clock)
    }

    private data class Lists(
        val day: Pair<LocalDate, List<Task>>,
        val open: List<Task>,
        val inbox: Pair<Long?, List<Task>>,
        val picked: List<Task>,
    )

    private val lists = combine(dayList, taskRepository.getAllOpenTasksFlat(), inboxList, pickedList) { d, open, inbox, picked ->
        Lists(d, open, inbox, picked)
    }

    /** What is stored now. A new collector reads Room again, which is how the request sees a fresh read. */
    private val session: Flow<PlanSessionData> = combine(lists, routines, settings, sharing) { l, routineList, s, share ->
        PlanSessionData(
            day = l.day.first,
            today = share.third.date,
            zone = share.third.zone,
            dayTasks = l.day.second,
            open = l.open,
            inbox = l.inbox.second,
            picked = l.picked,
            projects = share.first,
            collab = share.second,
            inboxId = l.inbox.first,
            routines = routineList,
            hours = s.hours,
            defaultLength = s.length,
            nudge = s.nudge,
        )
    }

    val uiState: StateFlow<PlanMyDayUiState> = combine(session, choices, answers) { data, chosen, answer ->
        planMyDayState(data, chosen, answer, time.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanMyDayUiState())

    private var readJob: Job? = null
    private var sendJob: Job? = null

    /** The day the last request went out for: turning the switch on again does not ask for it twice. */
    private var askedFor: LocalDate? = null

    init {
        viewModelScope.launch {
            val ready = try {
                suggester.isReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            choices.update { it.copy(assistantReady = ready) }
            readThenAsk(currentDay(), ask = ready)
        }
    }

    private fun currentDay(): LocalDate = dayClock.day.value.date.plus(choices.value.dayOffset, DateTimeUnit.DAY)

    // --- The assistant (ai-assistant 10.1) -------------------------------------------------------

    /**
     * Reads the day from the server, then asks when [ask] says so. What is on its way is for another
     * day or has been asked again, so it stops here. Offline the read fails and the request goes out
     * with what Room has.
     */
    private fun readThenAsk(day: LocalDate, ask: Boolean) {
        readJob?.cancel()
        sendJob?.cancel()
        answers.update { it.copy(loading = ask) }
        readJob = viewModelScope.launch {
            readFromServer(day)
            if (ask && choices.value.assistantActive && currentDay() == day) {
                sendJob = viewModelScope.launch { send(day) }
            } else {
                answers.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun readFromServer(day: LocalDate) {
        try {
            // A full read: a task deleted elsewhere goes, and a block removed elsewhere is read as gone.
            refresher.refresh(manual = true)
            // The only way the day's done blocks reach the phone.
            taskRepository.refreshPlanDay(day)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The request can still go out with what is stored; a failure of its own is said then.
        }
    }

    private suspend fun send(day: LocalDate) {
        askedFor = day
        try {
            val data = session.first { it.day == day }
            answers.update { it.copy(loading = true, answered = null, failed = null) }
            val result = suggester.suggest(planDayRequest(data, time.now()))
            answers.update { it.copy(loading = false, answered = day to result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Said once; the session goes on with first free slots and the default length (10.4).
            val message = "No suggestions: " + (e.message ?: "the model did not answer.")
            answers.update { it.copy(loading = false, failed = day to message) }
            appMessages.post(message)
        }
    }

    fun askAgain() {
        if (!choices.value.assistantActive) return
        readThenAsk(currentDay(), ask = true)
    }

    /** Off stops what is on its way and shows nothing; on asks only if this day was never asked. */
    fun setAssistantOn(on: Boolean) {
        choices.update { it.copy(assistantOn = on) }
        if (!on) {
            sendJob?.cancel()
            answers.update { it.copy(loading = false) }
        } else if (choices.value.assistantReady && askedFor != currentDay()) {
            readThenAsk(currentDay(), ask = true)
        }
    }

    fun setArrangeByAssistant(byAssistant: Boolean) = choices.update { it.copy(byAssistant = byAssistant) }

    // --- The steps -------------------------------------------------------------------------------

    fun setStep(step: Int) = choices.update { it.copy(step = step.coerceIn(STEP_LEFTOVERS, STEP_READY)) }

    /** The day can be changed in the first two steps. Its ticks and placements do not carry over. */
    fun setDay(offset: Int) {
        val current = choices.value
        if (current.dayOffset == offset || current.step > STEP_CHOOSE) return
        choices.update { it.copy(dayOffset = offset, picked = null, pins = emptyMap(), keep = emptySet()) }
        readThenAsk(currentDay(), ask = current.assistantActive)
    }

    // --- Leftovers: written at once --------------------------------------------------------------

    fun doLeftover(task: Task, action: LeftoverAction) {
        choices.update { it.copy(handled = it.handled + (task.id to HandledLeftover(task, handledLabel(action)))) }
        viewModelScope.launch {
            if (action == LeftoverAction.DONE) {
                complete(task.id)
            } else {
                writeNow(task.id, "move the task") { current ->
                    PlanDay.leftoverChanges(current, action, time.now(), dayClock.day.value.zone)
                }
            }
        }
    }

    fun applySuggestions() {
        for ((task, action) in uiState.value.suggestedLeftovers) doLeftover(task, action)
    }

    // --- Choose ----------------------------------------------------------------------------------

    fun toggleTick(id: Long) {
        val ticked = uiState.value.ticked
        choices.update { it.copy(picked = if (id in ticked) ticked - id else ticked + id) }
    }

    fun cycleLength(id: Long) {
        val state = uiState.value
        val pin = choices.value.pins[id]
        val current = pin?.let { it.end - it.start } ?: state.lengths[id] ?: state.defaultLength
        val next = PlanDay.LENGTH_CYCLE[(PlanDay.LENGTH_CYCLE.indexOf(current) + 1) % PlanDay.LENGTH_CYCLE.size]
        choices.update {
            it.copy(
                lengths = it.lengths + (id to next),
                pins = if (pin != null) it.pins + (id to clampedRange(pin.start, next)) else it.pins,
            )
        }
    }

    fun setProject(id: Long?) = choices.update { it.copy(projectId = id) }

    /** What can wait: the next day, written at once, and no longer ticked. */
    fun moveToNextDay(task: Task) {
        val state = uiState.value
        val day = state.day ?: return
        choices.update { it.copy(picked = state.ticked - task.id) }
        viewModelScope.launch {
            writeNow(task.id, "move the task") { current -> PlanDay.canWaitChanges(current, day, state.zone) }
        }
    }

    // --- Arrange: a draft until Accept -----------------------------------------------------------

    fun toggleKeep(id: Long) {
        choices.update {
            it.copy(
                keep = if (id in it.keep) it.keep - id else it.keep + id,
                pins = it.pins - id,
            )
        }
    }

    private fun pin(id: Long, start: Int, end: Int) {
        choices.update {
            it.copy(
                pins = it.pins + (id to MinuteRange(start, end)),
                lengths = it.lengths + (id to end - start),
                keep = it.keep - id,
            )
        }
    }

    private fun clampedRange(start: Int, length: Int): MinuteRange =
        MinuteRange(start, minOf(PlannerTimeline.DAY_MINUTES, start + length))

    /** A dragged block: the draft's own, or a saved block the session writes and can take back. */
    fun moveBlock(task: Task, startMinute: Int) {
        val state = uiState.value
        val draft = state.draftOf(task.id)
        if (draft != null) {
            pin(task.id, startMinute, startMinute + (draft.end - draft.start))
            return
        }
        changeSavedBlock(task, "move the block") { current, block ->
            TimeBlocks.placeBlock(current.dueDate, TimeBlocks.atClock(block.day, startMinute, state.zone), block.minutes, state.zone)
        }
    }

    fun resizeBlock(task: Task, endMinute: Int) {
        val state = uiState.value
        val draft = state.draftOf(task.id)
        if (draft != null) {
            pin(task.id, draft.start, endMinute)
            return
        }
        changeSavedBlock(task, "change the length") { _, block ->
            TimeBlocks.resizeEnd(block, TimeBlocks.atClock(block.day, endMinute, state.zone), state.zone)
        }
    }

    fun nudgeBlock(task: Task, step: Int) {
        val state = uiState.value
        val draft = state.draftOf(task.id)
        if (draft != null) {
            val length = draft.end - draft.start
            val start = (draft.start + step).coerceIn(0, PlannerTimeline.DAY_MINUTES - length)
            pin(task.id, start, start + length)
            return
        }
        changeSavedBlock(task, "move the block") { current, block ->
            val start = (TimeBlocks.minutesOfDay(block.start, state.zone) + step)
                .coerceIn(0, PlannerTimeline.DAY_MINUTES - block.minutes)
            TimeBlocks.placeBlock(current.dueDate, TimeBlocks.atClock(block.day, start, state.zone), block.minutes, state.zone)
        }
    }

    fun setBlockLength(task: Task, minutes: Int) {
        val state = uiState.value
        val draft = state.draftOf(task.id)
        if (draft != null) {
            val range = clampedRange(draft.start, minutes)
            pin(task.id, range.start, range.end)
            return
        }
        changeSavedBlock(task, "change the length") { _, block ->
            TimeBlocks.resizeEnd(block, block.start + minutes.minutes, state.zone)
        }
    }

    /** A saved block loses its time; from the draft a task is kept without one. */
    fun removeTime(task: Task) {
        if (uiState.value.draftOf(task.id) != null) {
            toggleKeep(task.id)
            return
        }
        viewModelScope.launch { writeNow(task.id, "remove the time") { TimeBlocks.removeTime(it.reminders) } }
    }

    private fun changeSavedBlock(task: Task, doing: String, change: (Task, com.rendyhd.vicu.util.TimeBlock) -> BlockChanges?) {
        val zone = uiState.value.zone
        viewModelScope.launch {
            writeNow(task.id, doing) { current -> TimeBlocks.blockOf(current, zone)?.let { change(current, it) } }
        }
    }

    /** One patch per task. They are all written even when the screen is closed in the middle. */
    fun accept() {
        val state = uiState.value
        val day = state.day ?: return
        if (state.accepting) return
        val drafts = state.draft.placed.associateBy { it.id }
        val tasks = state.toPlace.map { it.id }
        choices.update { it.copy(accepting = true) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                for (id in tasks) {
                    writeNow(id, "plan the task") { current -> PlanDay.acceptChanges(current, day, drafts[id], state.zone) }
                }
            }
            choices.update { it.copy(accepting = false, step = STEP_READY) }
        }
    }

    // --- Ready -----------------------------------------------------------------------------------

    /** Writes back what the session changed, reminders and completions included. It deletes nothing. */
    fun undoAll() {
        val entries = log.entries
        log.clear()
        choices.update {
            it.copy(
                step = STEP_LEFTOVERS,
                picked = null,
                keep = emptySet(),
                pins = emptyMap(),
                lengths = emptyMap(),
                handled = emptyMap(),
                remindersOn = false,
            )
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                writing.withLock {
                    for (entry in entries) {
                        if (entry.completed) {
                            appMessages.postIfRefused(taskRepository.setDone(entry.before.id, false), "reopen the task")
                        }
                        val current = taskRepository.getById(entry.before.id).first() ?: continue
                        appMessages.postIfRefused(taskRepository.update(entry.undoneOn(current)), "put the task back")
                    }
                }
            }
        }
    }

    /** Remind me when each block starts: written at once, and logged. */
    fun setBlockReminders(on: Boolean) {
        val state = uiState.value
        choices.update { it.copy(remindersOn = on) }
        val blocks = state.withTime.filter(state::canChange).map { it.id }
        viewModelScope.launch {
            for (id in blocks) {
                writeNow(id, "change the reminder") { current ->
                    val next = if (on) {
                        PlanDay.withStartReminder(current.reminders, current.startDate)
                    } else {
                        PlanDay.withoutStartReminder(current.reminders)
                    }
                    BlockChanges(reminders = next).takeIf { next.size != current.reminders.size }
                }
            }
        }
    }

    /** Ask me to plan tomorrow, per device. */
    fun setNudge(on: Boolean) {
        val time = uiState.value.nudge ?: PlannerNudge.DEFAULT_TIME
        viewModelScope.launch { behaviorPrefsStore.setPlannerNudge(if (on) time else null) }
    }

    fun minutesNow(): Int = dayClock.minutesNow()

    // --- Writing ---------------------------------------------------------------------------------

    /** On the task as it is stored now, so a row composed earlier cannot write old values. */
    private suspend fun writeNow(taskId: Long, doing: String, change: (Task) -> BlockChanges?) = writing.withLock {
        val current = taskRepository.getById(taskId).first() ?: return@withLock
        val changes = change(current) ?: return@withLock
        log.changed(current, changes)
        appMessages.postIfRefused(taskRepository.update(changes.applyTo(current)), doing)
    }

    private suspend fun complete(taskId: Long) = writing.withLock {
        val current = taskRepository.getById(taskId).first() ?: return@withLock
        log.completed(current)
        appMessages.postIfRefused(taskRepository.setDone(taskId, true), "complete the task")
    }

    private fun handledLabel(action: LeftoverAction): String = when (action) {
        LeftoverAction.TODAY -> "Moved to today"
        LeftoverAction.TOMORROW -> "Moved to tomorrow"
        LeftoverAction.NO_DATE -> "Date removed"
        LeftoverAction.DONE -> "Done"
    }
}

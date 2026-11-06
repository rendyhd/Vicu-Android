package com.rendyhd.vicu.ui.screens.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.PlannerPreferences
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.navigation.ProjectNode
import com.rendyhd.vicu.ui.navigation.buildProjectTree
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.BlockChanges
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.PlanSource
import com.rendyhd.vicu.util.PlannerHours
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.postIfRefused
import com.rendyhd.vicu.util.sortProjectTasks
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** A project of the drawer's list with how far down the tree it sits. */
data class PlanProjectRow(val project: Project, val depth: Int)

data class PlanUiState(
    val isLoading: Boolean = true,
    val today: LocalDate? = null,
    val zone: TimeZone = TimeZone.UTC,
    val selectedDay: LocalDate? = null,
    val strip: List<PlanStripDay> = emptyList(),
    /** The strip is not on the seven days from today: the bar offers a way back. */
    val stripMoved: Boolean = false,
    val content: PlanDayContent? = null,
    val routines: List<TimelineRoutine> = emptyList(),
    val hours: PlannerHours = TimeBlocks.DEFAULT_HOURS,
    val defaultLength: Int = TimeBlocks.DEFAULT_LENGTH_MINUTES,
    val projects: Map<Long, Project> = emptyMap(),
    val collab: CollabState = CollabState(),
    /** The list the drawer shows, and its tasks through the filter box. */
    val source: PlanSource = PlanSource.Day,
    val inboxProjectId: Long? = null,
    val filter: String = "",
    val listed: List<Task> = emptyList(),
    val listedCount: Int = 0,
    val projectRows: List<PlanProjectRow> = emptyList(),
) {
    /** A block follows the task's permissions (contract 16.8). */
    fun canChange(task: Task): Boolean = collab.access(task.projectId).canWrite

    /** A block cannot be placed on a past day (contract 16.2). */
    val canPlace: Boolean
        get() = today != null && selectedDay != null && PlannerTimeline.canPlaceOn(selectedDay, today)
}

/**
 * The Plan screen (docs/cross-app-semantics-v1.md section 16): one day at a time on a timeline, a
 * 7-day strip, and a drawer with the tasks to plan. Everything is read from Room; a day's own request
 * brings the done blocks in. Every change goes through [TaskRepository.update], so it is a merge patch
 * and queues offline like any other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(
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
    private val dayClock: DayClock,
    private val appMessages: AppMessages,
    private val time: TimeSource = SystemTimeSource,
) : ViewModel() {

    /** How many weeks from today the strip is, and which of its seven days is selected. */
    private val stripWeeks = MutableStateFlow(0)
    private val selectedOffset = MutableStateFlow(0)
    private val filter = MutableStateFlow("")

    private val stripStart: Flow<LocalDate> = combine(dayClock.today, stripWeeks) { today, weeks ->
        today.plus(weeks * PLAN_STRIP_DAYS, DateTimeUnit.DAY)
    }.distinctUntilChanged()

    private val selectedDay: Flow<LocalDate> = combine(stripStart, selectedOffset) { start, offset ->
        start.plus(offset, DateTimeUnit.DAY)
    }.distinctUntilChanged()

    private val projects = projectRepository.getAll()

    /** The strip's days, overdue tasks with them while today is on it. Only what is on this screen. */
    private val rangeTasks: Flow<List<Task>> = stripStart
        .flatMapLatest { start -> taskRepository.getPlanTasks(start, start.plus(PLAN_STRIP_DAYS - 1, DateTimeUnit.DAY)) }

    private val source: Flow<PlanSource> = behaviorPrefsStore.getPrefs()
        .map { PlanSource.read(it.plannerSource) }
        .distinctUntilChanged()

    private val taskOrders = taskOrderPreferences.settings

    /** The drawer's list when it is not the selected day, which comes from the same request as the timeline. */
    private val otherList: Flow<List<Task>> = combine(source, authManager.inboxProjectId) { list, inbox -> list to inbox }
        .distinctUntilChanged()
        .flatMapLatest { (list, inbox) ->
            when (list) {
                PlanSource.Day -> flowOf(emptyList())
                PlanSource.NextSeven -> combine(taskRepository.getUpcomingTasks(), dayClock.day) { tasks, day ->
                    tasks.filter { PlanSource.inNextSevenDays(it.dueDate, day.date, day.zone) }
                }
                PlanSource.NoDate -> taskRepository.getAnytimeTasks(inbox ?: 0L).map { tasks ->
                    tasks.filter { DueDates.localDateOf(it.dueDate, dayClock.day.value.zone) == null }
                }
                PlanSource.Inbox, is PlanSource.Project -> {
                    val projectId = list.projectId(inbox) ?: return@flatMapLatest flowOf(emptyList())
                    combine(taskRepository.getByProjectId(projectId), taskOrders, projects) { tasks, orders, all ->
                        sortProjectTasks(tasks.filter { !it.done }, orders.orderFor(projectId, all))
                    }
                }
            }
        }

    private val routines: Flow<List<TimelineRoutine>> = combine(
        selectedDay,
        routinePrefsStore.enabled,
        behaviorPrefsStore.getPrefs().map { it.plannerShowRoutines }.distinctUntilChanged(),
    ) { day, enabled, shown -> day.takeIf { enabled && shown }?.toString() to day.toString() }
        .distinctUntilChanged()
        .flatMapLatest { (date, day) ->
            if (date != null) routineRepository.observeDay(date) else flowOf(RoutineDay(day, emptyList()))
        }
        .map(::routinesOnTimeline)

    private data class Prefs(val hours: PlannerHours, val length: Int)
    private data class DayInputs(val tasks: List<Task>, val day: LocalDate, val start: LocalDate, val weeks: Int)
    private data class ListInputs(
        val source: PlanSource,
        val other: List<Task>,
        val filter: String,
        val inbox: Long?,
    )

    private val prefs = combine(plannerPreferences.hours, plannerPreferences.defaultLength) { hours, length ->
        Prefs(hours, length)
    }

    /**
     * The planner is off (Settings): this screen has no business being open. It reads the setting each
     * time it is collected and is not part of [uiState] on purpose: navigation keeps this view model when
     * the screen leaves, and an "off" held there sent a Plan opened later straight away again.
     */
    val plannerOff: Flow<Boolean> = behaviorPrefsStore.getPrefs().map { !it.plannerEnabled }.distinctUntilChanged()

    private val dayInputs = combine(rangeTasks, selectedDay, stripStart, stripWeeks) { tasks, day, start, weeks ->
        DayInputs(tasks, day, start, weeks)
    }

    private val listInputs = combine(source, otherList, filter, authManager.inboxProjectId) { list, other, text, inbox ->
        ListInputs(list, other, text, inbox)
    }

    private val sharing = combine(projects, collaborationRepository.state) { all, collab -> all to collab }

    val uiState: StateFlow<PlanUiState> = combine(
        dayInputs,
        listInputs,
        prefs,
        routines,
        combine(sharing, dayClock.day) { share, day -> share to day },
    ) { day, list, prefs, routines, (share, clock) ->
        build(day, list, prefs, routines, share.first, share.second, clock)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanUiState())

    private fun build(
        input: DayInputs,
        list: ListInputs,
        prefs: Prefs,
        routines: List<TimelineRoutine>,
        projects: List<Project>,
        collab: CollabState,
        clock: ClockDay,
    ): PlanUiState {
        val byId = projects.associateBy { it.id }
        // Tasks of an archived or unknown project are not on the timeline, as on every list.
        val tasks = input.tasks.filter { it.projectId in byId }
        val content = planDayContent(tasks, input.day, time.now(), prefs.hours, clock.zone)
        // The stored list outlives a project (archived, deleted, another account): back to the first list.
        val source = list.source.takeUnless { it is PlanSource.Project && it.id !in byId } ?: PlanSource.Day
        val all = when (source) {
            PlanSource.Day -> content.withoutTime
            else -> list.other.filter { it.projectId in byId }.withoutNestedSubtasks(hideChildrenOfCompletedParents = false)
        }
        return PlanUiState(
            isLoading = false,
            today = clock.date,
            zone = clock.zone,
            selectedDay = input.day,
            strip = planStrip(input.start, tasks, prefs.hours, clock.zone),
            stripMoved = input.weeks != 0,
            content = content,
            routines = routines,
            hours = prefs.hours,
            defaultLength = prefs.length,
            projects = byId,
            collab = collab,
            source = source,
            inboxProjectId = list.inbox,
            filter = list.filter,
            listed = all.filter { PlanSource.matchesTitleFilter(it.title, list.filter) },
            listedCount = all.size,
            projectRows = projectRows(projects, list.inbox),
        )
    }

    init {
        // One request per day on screen: its done blocks are not otherwise on the phone.
        viewModelScope.launch {
            selectedDay.collect { day -> taskRepository.refreshPlanDay(day) }
        }
        // A project's manual order needs the server's positions, as on the project page.
        viewModelScope.launch {
            combine(source, authManager.inboxProjectId) { list, inbox -> list.projectId(inbox) }
                .distinctUntilChanged()
                .collect { id -> if (id != null) taskRepository.refreshListPositions(id) }
        }
        if (refresher.isStale()) viewModelScope.launch { refresher.refresh(manual = false) }
    }

    // --- The strip -------------------------------------------------------------------------------

    fun selectDay(day: LocalDate, stripStartDay: LocalDate) {
        selectedOffset.value = (day.toEpochDays() - stripStartDay.toEpochDays()).toInt().coerceIn(0, PLAN_STRIP_DAYS - 1)
    }

    /** Another week on the strip, the same weekday selected. */
    fun moveStrip(weeks: Int) {
        stripWeeks.value += weeks
    }

    fun goToday() {
        stripWeeks.value = 0
        selectedOffset.value = 0
    }

    // --- The drawer ------------------------------------------------------------------------------

    fun setSource(next: PlanSource) {
        filter.value = ""
        viewModelScope.launch { behaviorPrefsStore.setPlannerSource(next.key) }
    }

    fun setFilter(text: String) {
        filter.value = text
    }

    // --- Writing a block (contract 16.2) ---------------------------------------------------------

    /** Why a task cannot take a time now, or null. */
    fun placingProblem(task: Task): String? {
        val state = uiState.value
        return when {
            !state.canPlace -> PAST_DAY
            !state.canChange(task) -> VIEW_ONLY
            else -> null
        }
    }

    /** Puts [task] on the selected day at [minute]: its own length if it has a block, else the default. */
    fun placeAt(task: Task, minute: Int) {
        val state = uiState.value
        val day = state.selectedDay ?: return
        placingProblem(task)?.let {
            appMessages.post(it)
            return
        }
        write(task.id, "place the task") { current ->
            val length = TimeBlocks.blockOf(current, state.zone)?.minutes ?: state.defaultLength
            TimeBlocks.placeBlock(current.dueDate, TimeBlocks.atClock(day, minute, state.zone), length, state.zone)
        }
    }

    /** Puts [task] in the first free slot of the selected day; says so when there is none (contract 16.6). */
    fun placeInNextFree(task: Task): Boolean {
        val state = uiState.value
        val day = state.selectedDay ?: return false
        val length = TimeBlocks.blockOf(task, state.zone)?.minutes ?: state.defaultLength
        val busy = TimeBlocks.busyOfDay(state.content?.blocks.orEmpty().map { it.task }, day, state.zone)
        val slot = TimeBlocks.nextFreeSlot(time.now(), day, state.hours, length, busy, state.zone)
        if (slot == null) {
            appMessages.post("Nothing fits in the free time left.")
            return false
        }
        placeAt(task, slot)
        return true
    }

    /** A block to [startMinute] on its own day, the same length. */
    fun move(task: Task, startMinute: Int) {
        val zone = uiState.value.zone
        write(task.id, "move the block") { current ->
            val block = TimeBlocks.blockOf(current, zone) ?: return@write null
            TimeBlocks.placeBlock(current.dueDate, TimeBlocks.atClock(block.day, startMinute, zone), block.minutes, zone)
        }
    }

    /** The block's end to [endMinute] of its day. */
    fun resize(task: Task, endMinute: Int) {
        val zone = uiState.value.zone
        write(task.id, "change the length") { current ->
            val block = TimeBlocks.blockOf(current, zone) ?: return@write null
            TimeBlocks.resizeEnd(block, TimeBlocks.atClock(block.day, endMinute, zone), zone)
        }
    }

    /** An exact length. */
    fun setLength(task: Task, minutes: Int) {
        val zone = uiState.value.zone
        write(task.id, "change the length") { current ->
            val block = TimeBlocks.blockOf(current, zone) ?: return@write null
            TimeBlocks.resizeEnd(block, block.start + minutes.minutes, zone)
        }
    }

    /** Earlier or later by [step] minutes, inside its day. */
    fun nudge(task: Task, step: Int) {
        val zone = uiState.value.zone
        write(task.id, "move the block") { current ->
            val block = TimeBlocks.blockOf(current, zone) ?: return@write null
            val start = (TimeBlocks.minutesOfDay(block.start, zone) + step)
                .coerceIn(0, PlannerTimeline.DAY_MINUTES - block.minutes)
            TimeBlocks.placeBlock(current.dueDate, TimeBlocks.atClock(block.day, start, zone), block.minutes, zone)
        }
    }

    /** Both dates go, and the reminders that followed them. The due date stays. */
    fun removeTime(task: Task) {
        write(task.id, "remove the time") { current -> TimeBlocks.removeTime(current.reminders) }
    }

    /** A task with only a due time gets a block from that time (the marker's sheet). */
    fun addBlockAtDueTime(task: Task) {
        val state = uiState.value
        write(task.id, "add the block") { current ->
            val due: Instant = com.rendyhd.vicu.util.DateUtils.parseIsoDate(current.dueDate) ?: return@write null
            val day = DueDates.localDateOf(due, state.zone)
            if (state.today != null && !PlannerTimeline.canPlaceOn(day, state.today)) return@write null
            val start = TimeBlocks.atClock(day, TimeBlocks.minutesOfDay(due, state.zone), state.zone)
            TimeBlocks.placeBlock(current.dueDate, start, state.defaultLength, state.zone)
        }
    }

    private fun write(taskId: Long, doing: String, change: (Task) -> BlockChanges?) {
        viewModelScope.launch {
            // On the task as it is stored now, so a row composed earlier cannot write old values.
            val current = taskRepository.getById(taskId).first() ?: return@launch
            if (!uiState.value.canChange(current)) {
                appMessages.post(VIEW_ONLY)
                return@launch
            }
            val changes = change(current) ?: return@launch
            appMessages.postIfRefused(taskRepository.update(changes.applyTo(current)), doing)
        }
    }

    // --- Ticking ---------------------------------------------------------------------------------

    /** A done block stays on the timeline with its check, so a second tap is the undo. */
    fun toggleDone(task: Task) {
        if (!uiState.value.canChange(task)) {
            appMessages.post(VIEW_ONLY)
            return
        }
        viewModelScope.launch {
            if (task.done) {
                appMessages.postIfRefused(taskRepository.setDone(task.id, false), "reopen the task")
            } else {
                appMessages.postIfRefused(taskRepository.toggleDone(task), "complete the task")
            }
        }
    }

    fun minutesNow(): Int = dayClock.minutesNow()

    fun postMessage(text: String) = appMessages.post(text)

    companion object {
        const val PAST_DAY = "A task cannot be planned on a past day."
        const val VIEW_ONLY = "You can only view this project."

        /** Every project with its subprojects, the Inbox left out: it is a list of its own. */
        internal fun projectRows(projects: List<Project>, inboxId: Long?): List<PlanProjectRow> {
            val tree = buildProjectTree(projects.filter { !it.isArchived && it.id != inboxId })
            val rows = mutableListOf<PlanProjectRow>()
            fun visit(node: ProjectNode, depth: Int) {
                rows += PlanProjectRow(node.project, depth)
                node.children.forEach { visit(it, depth + 1) }
            }
            tree.forEach { visit(it, 0) }
            return rows
        }
    }
}

package com.rendyhd.vicu.ui.screens.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.PlannerPreferences
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.navigation.ProjectNode
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.ui.navigation.buildProjectTree
import com.rendyhd.vicu.calendar.PlanCalendarDays
import com.rendyhd.vicu.calendar.PlanCalendars
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.CalendarEvents
import com.rendyhd.vicu.util.DefaultReminder
import com.rendyhd.vicu.util.EditValue
import com.rendyhd.vicu.util.EventEdit
import com.rendyhd.vicu.util.EventRepeat
import com.rendyhd.vicu.util.EventScope
import com.rendyhd.vicu.util.GestureRange
import com.rendyhd.vicu.util.IcsImport
import com.rendyhd.vicu.util.ListedEvent
import com.rendyhd.vicu.util.PlanEvent
import com.rendyhd.vicu.util.PlanEvents
import kotlinx.serialization.json.JsonElement
import com.rendyhd.vicu.util.BlockChanges
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.NetworkResult
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
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
    /** The days on either side of the selected one, for a swipe to show before it lands. */
    val previous: PlanDayContent? = null,
    val next: PlanDayContent? = null,
    /** The list the drawer shows, and its tasks through the filter box. */
    val source: PlanSource = PlanSource.Day,
    /** How many tasks each of the four lists holds, by [PlanSource.key]. */
    val sourceCounts: Map<String, Int> = emptyMap(),
    /** Top-row tasks ticked a moment ago, still shown struck through. */
    val heldIds: Set<Long> = emptySet(),
    val inboxProjectId: Long? = null,
    val filter: String = "",
    val listed: List<Task> = emptyList(),
    val listedCount: Int = 0,
    val projectRows: List<PlanProjectRow> = emptyList(),
    /** Your own Events and the phone's calendars for the days around the strip (contract 20). */
    val calendar: PlanCalendarDays = PlanCalendarDays(),
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
    private val refresher: ScreenRefresher,
    private val dayClock: DayClock,
    private val appMessages: AppMessages,
    private val planCalendars: PlanCalendars,
    private val notificationPrefsStore: NotificationPrefsStore,
    private val authManager: AuthManager,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
    private val time: TimeSource = SystemTimeSource,
) : ViewModel() {

    /** A task ticked in the top row stays there, struck through, for the hold (contract 7). */
    val completions = CompletionHold(viewModelScope, navigationTicker = navigationTicker, toast = completionToast)

    /** How many weeks from today the strip is, and which of its seven days is selected: one value, so both change together. */
    private data class StripPosition(val weeks: Int, val offset: Int)

    private val position = MutableStateFlow(StripPosition(0, 0))
    private val filter = MutableStateFlow("")

    private val stripStart: Flow<LocalDate> = combine(dayClock.today, position) { today, at ->
        today.plus(at.weeks * PLAN_STRIP_DAYS, DateTimeUnit.DAY)
    }.distinctUntilChanged()

    private val selectedDay: Flow<LocalDate> = combine(dayClock.today, position) { today, at ->
        today.plus(at.weeks * PLAN_STRIP_DAYS + at.offset, DateTimeUnit.DAY)
    }.distinctUntilChanged()

    private val projects = projectRepository.getAll()

    /**
     * The strip's days and one more on each side (a swipe shows the next day before it lands), overdue
     * tasks with them while today is among them. Only what is on this screen.
     */
    private val rangeTasks: Flow<List<Task>> = stripStart
        .flatMapLatest { start ->
            taskRepository.getPlanTasks(start.minus(1, DateTimeUnit.DAY), start.plus(PLAN_STRIP_DAYS, DateTimeUnit.DAY))
        }

    /**
     * Your own Events and the shown phone calendars for the same days as [rangeTasks]. It starts
     * empty, so the timeline never waits for a calendar to be read.
     */
    private val calendarDays: Flow<PlanCalendarDays> = combine(stripStart, dayClock.day) { start, clock -> start to clock.zone }
        .distinctUntilChanged()
        .flatMapLatest { (start, zone) ->
            planCalendars.observe(start.minus(1, DateTimeUnit.DAY), start.plus(PLAN_STRIP_DAYS, DateTimeUnit.DAY), zone)
                .onStart { emit(PlanCalendarDays()) }
        }

    private val source: Flow<PlanSource> = behaviorPrefsStore.getPrefs()
        .map { PlanSource.read(it.plannerSource) }
        .distinctUntilChanged()

    private val taskOrders = taskOrderPreferences.settings

    /** A project's open tasks in its task order: the Inbox's list and a project's. */
    private fun projectList(projectId: Long?): Flow<List<Task>> {
        if (projectId == null) return flowOf(emptyList())
        return combine(taskRepository.getByProjectId(projectId), taskOrders, projects) { tasks, orders, all ->
            sortProjectTasks(tasks.filter { !it.done }, orders.orderFor(projectId, all))
        }
    }

    /**
     * The drawer's other lists. The selected day's comes from the same request as the timeline. The
     * Inbox, the next seven days and No date are read even while another list is shown, because
     * their chips say how many tasks each holds.
     */
    private val sourceLists: Flow<SourceLists> = combine(source, authManager.inboxProjectId) { list, inbox -> list to inbox }
        .distinctUntilChanged()
        .flatMapLatest { (list, inbox) ->
            val nextSeven = combine(taskRepository.getUpcomingTasks(), dayClock.day) { tasks, day ->
                tasks.filter { PlanSource.inNextSevenDays(it.dueDate, day.date, day.zone) }
            }
            val noDate = taskRepository.getAnytimeTasks(inbox ?: 0L).map { tasks ->
                tasks.filter { DueDates.localDateOf(it.dueDate, dayClock.day.value.zone) == null }
            }
            // The counts start at nothing, so the timeline never waits for a list it does not show.
            combine(
                projectList(PlanSource.Inbox.projectId(inbox)).onStart { emit(emptyList()) },
                nextSeven.onStart { emit(emptyList()) },
                noDate.onStart { emit(emptyList()) },
                projectList((list as? PlanSource.Project)?.id),
            ) { inboxTasks, next, none, project -> SourceLists(inboxTasks, next, none, project) }
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
    private data class SourceLists(
        val inbox: List<Task>,
        val nextSeven: List<Task>,
        val noDate: List<Task>,
        val project: List<Task>,
    )
    private data class ListInputs(
        val source: PlanSource,
        val lists: SourceLists,
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

    private val dayInputs = combine(rangeTasks, selectedDay, stripStart, position) { tasks, day, start, at ->
        DayInputs(tasks, day, start, at.weeks)
    }

    private val listInputs = combine(source, sourceLists, filter, authManager.inboxProjectId) { list, lists, text, inbox ->
        ListInputs(list, lists, text, inbox)
    }

    private val sharing = combine(projects, collaborationRepository.state) { all, collab -> all to collab }

    val uiState: StateFlow<PlanUiState> = combine(
        dayInputs,
        listInputs,
        prefs,
        combine(routines, calendarDays) { r, c -> r to c },
        combine(sharing, dayClock.day, completions.state) { share, day, held -> Triple(share, day, held.keys) },
    ) { day, list, prefs, (routines, calendar), (share, clock, held) ->
        build(day, list, prefs, routines, calendar, share.first, share.second, clock, held)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanUiState())

    private fun build(
        input: DayInputs,
        list: ListInputs,
        prefs: Prefs,
        routines: List<TimelineRoutine>,
        calendar: PlanCalendarDays,
        projects: List<Project>,
        collab: CollabState,
        clock: ClockDay,
        held: Set<Long>,
    ): PlanUiState {
        val byId = projects.associateBy { it.id }
        // Tasks of an archived or unknown project are not on the timeline, as on every list.
        val tasks = input.tasks.filter { it.projectId in byId }
        val now = time.now()
        val isHeld: (Task) -> Boolean = { it.id in held }
        val counting = calendar.counting
        fun dayContent(day: LocalDate, held: (Task) -> Boolean = { false }) =
            planDayContent(tasks, day, now, prefs.hours, clock.zone, calendar.events, counting, held)
        val read = dayContent(input.day, isHeld)
        val content = read.copy(topRow = completions.merge(read.topRow))
        // The stored list outlives a project (archived, deleted, another account): back to the first list.
        val source = list.source.takeUnless { it is PlanSource.Project && it.id !in byId } ?: PlanSource.Day
        fun shown(tasks: List<Task>) =
            tasks.filter { it.projectId in byId }.withoutNestedSubtasks(hideChildrenOfCompletedParents = false)
        val inbox = shown(list.lists.inbox)
        val nextSeven = shown(list.lists.nextSeven)
        val noDate = shown(list.lists.noDate)
        val all = when (source) {
            PlanSource.Day -> content.withoutTime
            PlanSource.Inbox -> inbox
            PlanSource.NextSeven -> nextSeven
            PlanSource.NoDate -> noDate
            is PlanSource.Project -> shown(list.lists.project)
        }
        return PlanUiState(
            isLoading = false,
            heldIds = completions.completedIds,
            today = clock.date,
            zone = clock.zone,
            selectedDay = input.day,
            strip = planStrip(input.start, tasks, prefs.hours, clock.zone, calendar.events, counting),
            stripMoved = input.weeks != 0,
            content = content,
            previous = dayContent(input.day.minus(1, DateTimeUnit.DAY)),
            next = dayContent(input.day.plus(1, DateTimeUnit.DAY)),
            routines = routines,
            hours = prefs.hours,
            defaultLength = prefs.length,
            projects = byId,
            collab = collab,
            source = source,
            sourceCounts = mapOf(
                PlanSource.Day.key to content.withoutTime.size,
                PlanSource.Inbox.key to inbox.size,
                PlanSource.NextSeven.key to nextSeven.size,
                PlanSource.NoDate.key to noDate.size,
            ),
            inboxProjectId = list.inbox,
            filter = list.filter,
            listed = all.filter { PlanSource.matchesTitleFilter(it.title, list.filter) },
            listedCount = all.size,
            projectRows = projectRows(projects, list.inbox),
            calendar = calendar,
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
        val offset = (day.toEpochDays() - stripStartDay.toEpochDays()).toInt().coerceIn(0, PLAN_STRIP_DAYS - 1)
        position.update { it.copy(offset = offset) }
    }

    /** Another week on the strip, the same weekday selected. */
    fun moveStrip(weeks: Int) {
        position.update { it.copy(weeks = it.weeks + weeks) }
    }

    fun goToday() {
        position.value = StripPosition(0, 0)
    }

    /** Any [day]: the strip moves to the week that holds it when it is not on it. */
    fun showDay(day: LocalDate) {
        val days = (day.toEpochDays() - dayClock.day.value.date.toEpochDays()).toInt()
        position.value = StripPosition(days.floorDiv(PLAN_STRIP_DAYS), days.mod(PLAN_STRIP_DAYS))
    }

    /** The day before or after the selected one (a swipe on the timeline). */
    fun stepDay(days: Int) {
        val at = position.value
        showDay(dayClock.day.value.date.plus(at.weeks * PLAN_STRIP_DAYS + at.offset + days, DateTimeUnit.DAY))
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
        write(task.id, "place the task", announceDue = true) { current ->
            val length = TimeBlocks.blockOf(current, state.zone)?.minutes ?: state.defaultLength
            TimeBlocks.placeBlock(current.dueDate, TimeBlocks.atClock(day, minute, state.zone), length, state.zone)
        }
    }

    /** Puts [task] in the first free slot of the selected day; says so when there is none (contract 16.6). */
    fun placeInNextFree(task: Task): Boolean {
        val state = uiState.value
        val day = state.selectedDay ?: return false
        val length = TimeBlocks.blockOf(task, state.zone)?.minutes ?: state.defaultLength
        val events = CalendarEvents.eventBusyOfDay(
            state.calendar.events,
            day,
            state.zone,
            CalendarEvents.calendarCounts(state.calendar.counting),
        )
        val busy = TimeBlocks.busyOfDay(state.content?.blocks.orEmpty().map { it.task }, day, state.zone) + events
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

    /**
     * A block to [startMinute] on [day], the same length: a drag that went to another day through the
     * strip. On its own day it is [move].
     */
    fun moveTo(task: Task, day: LocalDate, startMinute: Int) {
        val state = uiState.value
        if (state.today != null && !PlannerTimeline.canPlaceOn(day, state.today)) {
            appMessages.post(PAST_DAY)
            return
        }
        write(task.id, "move the block", announceDue = true) { current ->
            val block = TimeBlocks.blockOf(current, state.zone) ?: return@write null
            TimeBlocks.placeBlock(current.dueDate, TimeBlocks.atClock(day, startMinute, state.zone), block.minutes, state.zone)
        }
    }

    /**
     * A block to another [day] at the same times (the sheet's Day row, a drop on a day of the strip),
     * contract 16.2. Says where it went, with Undo, and Plan follows it there.
     */
    fun moveToDay(task: Task, day: LocalDate) {
        val state = uiState.value
        if (state.today != null && !PlannerTimeline.canPlaceOn(day, state.today)) {
            appMessages.post(PAST_DAY)
            return
        }
        viewModelScope.launch {
            val current = taskRepository.getById(task.id).first() ?: return@launch
            if (!uiState.value.canChange(current)) {
                appMessages.post(VIEW_ONLY)
                return@launch
            }
            val block = TimeBlocks.blockOf(current, state.zone) ?: return@launch
            if (block.day == day) return@launch
            val result = taskRepository.update(TimeBlocks.moveToDay(current.dueDate, block, day, state.zone).applyTo(current))
            if (appMessages.postIfRefused(result, "move the block")) return@launch
            showDay(day)
            val today = state.today ?: day
            appMessages.post(
                message = "Moved to ${DateDisplay.formatWeekdayDate(day, today, format)}",
                actionLabel = "Undo",
            ) { restore(current) }
        }
    }

    /** Drop a task on a day without a time (the top row): no block, due that day (contract 16.2). */
    fun planForDay(task: Task, day: LocalDate) {
        val state = uiState.value
        if (state.today != null && !PlannerTimeline.canPlaceOn(day, state.today)) {
            appMessages.post(PAST_DAY)
            return
        }
        write(task.id, "remove the time") { current -> TimeBlocks.planForDay(current.reminders, day, state.zone) }
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

    /**
     * Writes [change] on the task as it is stored. With [announceDue], a block that moved the due date
     * to another day says so, with Undo: the due rule (16.2) is the one change nobody dragged.
     */
    private fun write(taskId: Long, doing: String, announceDue: Boolean = false, change: (Task) -> BlockChanges?) {
        viewModelScope.launch {
            // On the task as it is stored now, so a row composed earlier cannot write old values.
            val current = taskRepository.getById(taskId).first() ?: return@launch
            if (!uiState.value.canChange(current)) {
                appMessages.post(VIEW_ONLY)
                return@launch
            }
            val changes = change(current) ?: return@launch
            if (appMessages.postIfRefused(taskRepository.update(changes.applyTo(current)), doing)) return@launch
            if (announceDue) announceDueMove(current, changes)
        }
    }

    /** "Planned for Tue 10 Nov. Due date moved from Mon 9 Nov", with Undo, when the due rule moved it. */
    private fun announceDueMove(before: Task, changes: BlockChanges) {
        val state = uiState.value
        val today = state.today ?: return
        val from = DueDates.localDateOf(before.dueDate, state.zone) ?: return
        val to = DueDates.localDateOf(changes.dueDate ?: return, state.zone) ?: return
        if (from == to) return
        val planned = DateDisplay.formatWeekdayDate(to, today, format)
        val was = DateDisplay.formatWeekdayDate(from, today, format)
        appMessages.post(
            message = "Planned for $planned. Due date moved from $was",
            actionLabel = "Undo",
        ) { restore(before) }
    }

    /** Puts back the block, the due date and the reminders [before] had: Undo of a move. */
    private fun restore(before: Task) {
        viewModelScope.launch {
            val current = taskRepository.getById(before.id).first() ?: return@launch
            val back = BlockChanges(
                startDate = before.startDate.ifEmpty { TimeBlocks.CLEARED },
                endDate = before.endDate.ifEmpty { TimeBlocks.CLEARED },
                dueDate = before.dueDate.ifEmpty { TimeBlocks.CLEARED },
                reminders = before.reminders,
            )
            appMessages.postIfRefused(taskRepository.update(back.applyTo(current)), "undo the move")
        }
    }

    // --- Something new on an empty time --------------------------------------------------------

    /**
     * A task with a block at [startMinute] of [day], in the Inbox: the default length and the due
     * rule (contract 16.2), with the default reminder like any new task.
     */
    fun createTask(title: String, day: LocalDate, startMinute: Int) {
        val state = uiState.value
        val text = title.trim()
        if (text.isEmpty()) return
        if (state.today != null && !PlannerTimeline.canPlaceOn(day, state.today)) {
            appMessages.post(PAST_DAY)
            return
        }
        viewModelScope.launch {
            val inbox = authManager.inboxProjectId.first()
            if (inbox == null) {
                appMessages.post("Choose an Inbox project in Settings first.")
                return@launch
            }
            if (!state.collab.access(inbox).canWrite) {
                appMessages.post(VIEW_ONLY)
                return@launch
            }
            val start = TimeBlocks.atClock(day, startMinute, state.zone)
            val block = TimeBlocks.placeBlock(null, start, state.defaultLength, state.zone)
            var task = Task(
                id = 0,
                title = text,
                projectId = inbox,
                dueDate = block.dueDate.orEmpty(),
                startDate = block.startDate.orEmpty(),
                endDate = block.endDate.orEmpty(),
            )
            val prefs = notificationPrefsStore.getPrefs().first()
            DefaultReminder.build(task.dueDate, prefs.defaultReminderOffset, prefs.defaultReminderRelativeTo, task.startDate, task.endDate)
                ?.let { task = task.copy(reminders = listOf(it)) }
            when (val made = taskRepository.create(task)) {
                is NetworkResult.Success -> appMessages.post(
                    message = "Added to the Inbox",
                    actionLabel = "Undo",
                    openTaskId = made.data.id,
                ) { viewModelScope.launch { appMessages.postIfRefused(taskRepository.delete(made.data.id), "undo the new task") } }
                is NetworkResult.Error -> appMessages.post(made.message)
                is NetworkResult.Loading -> Unit
            }
        }
    }

    // --- Your Events (contract 20.3 and 20.6) -----------------------------------------------------

    /** A new Event on [day]; [startMinute] null makes it all-day. Undo removes it. */
    fun createEvent(title: String, day: LocalDate, startMinute: Int?, minutes: Int, repeat: EventRepeat) {
        val text = title.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val event = PlanEvent(
                title = text,
                start = day,
                time = startMinute?.let(TimeBlocks::clockText),
                minutes = if (startMinute == null) 0 else minutes.coerceIn(1, PlanEvents.MAX_EVENT_MINUTES),
                repeat = repeat,
            )
            val id = planCalendars.create(event)
            appMessages.post(message = "Event added", actionLabel = "Undo") {
                viewModelScope.launch { planCalendars.write(PlanEvents.delete(id)) }
            }
        }
    }

    /** The stored Event an occurrence comes from, read now (not from a state composed earlier). */
    private suspend fun storedEvent(occurrence: CalendarEvent): Pair<ListedEvent, Map<String, JsonElement?>>? {
        val id = occurrence.planEventId ?: return null
        val stored = planCalendars.storedEvents()
        val listed = PlanEvents.readAll(stored).firstOrNull { it.id == id } ?: return null
        return listed to stored
    }

    /** Whether changing [occurrence] asks All times, This time only or This and following. */
    fun repeats(occurrence: CalendarEvent): Boolean =
        uiState.value.calendar.ownEvent(occurrence.planEventId)?.event?.repeat.let { it != null && it != EventRepeat.None }

    /** Writes a change to the occurrence, in [scope] (contract 20.6), with Undo. */
    fun changeEvent(occurrence: CalendarEvent, edit: EventEdit, scope: EventScope) {
        val day = occurrence.day ?: return
        viewModelScope.launch {
            val (listed, before) = storedEvent(occurrence) ?: return@launch
            val writes = PlanEvents.change(listed, day, edit, scope, planCalendars.newId(), uiState.value.defaultLength)
            writeEvents(writes, before, "Event changed")
        }
    }

    /** A move or a new length on the timeline: the time and the length of the stretch it ended on. */
    fun moveEvent(occurrence: CalendarEvent, range: GestureRange, scope: EventScope) {
        changeEvent(
            occurrence,
            EventEdit(time = EditValue(TimeBlocks.clockText(range.startMinute)), minutes = range.endMinute - range.startMinute),
            scope,
        )
    }

    /** Skip this one: the day joins the skips. */
    fun skipEvent(occurrence: CalendarEvent) {
        val day = occurrence.day ?: return
        viewModelScope.launch {
            val (listed, before) = storedEvent(occurrence) ?: return@launch
            val today = uiState.value.today ?: day
            writeEvents(
                PlanEvents.skip(listed, day),
                before,
                "Skipped ${listed.event.title} on ${DateDisplay.formatWeekdayDate(day, today, format)}",
            )
        }
    }

    /** Delete: the Event goes on every device. */
    fun deleteEvent(occurrence: CalendarEvent) {
        viewModelScope.launch {
            val (listed, before) = storedEvent(occurrence) ?: return@launch
            writeEvents(PlanEvents.delete(listed.id), before, "Event deleted")
        }
    }

    /**
     * A calendar file's Events (contract 20.10): each under a new id, all in one change, with Undo,
     * which removes them again. Afterwards they are ordinary Events.
     */
    fun importEvents(events: List<PlanEvent>) {
        if (events.isEmpty()) return
        viewModelScope.launch {
            val writes = IcsImport.writes(events) { planCalendars.newId() }
            val message = if (events.size == 1) "Imported 1 event" else "Imported ${events.size} events"
            writeEvents(writes, planCalendars.storedEvents(), message)
        }
    }

    /** Writes [writes] and offers Undo, which puts back each entry as it was stored. */
    private suspend fun writeEvents(writes: Map<String, JsonElement?>, before: Map<String, JsonElement?>, message: String) {
        planCalendars.write(writes)
        val undo = PlanEvents.undo(writes, before)
        appMessages.post(message = message, actionLabel = "Undo") {
            viewModelScope.launch { planCalendars.write(undo) }
        }
    }

    // --- Ticking ---------------------------------------------------------------------------------

    /** A tick in the top row: the task stays there, struck through, for the hold; a second tap is the undo. */
    fun toggleTopRow(task: Task) {
        if (!uiState.value.canChange(task)) {
            appMessages.post(VIEW_ONLY)
            return
        }
        viewModelScope.launch {
            when {
                task.id in completions.state.value -> {
                    completions.undoing(task.id)
                    val result = taskRepository.setDone(task.id, false)
                    completions.release(task.id)
                    appMessages.postIfRefused(result, "reopen the task")
                }
                task.done -> appMessages.postIfRefused(taskRepository.setDone(task.id, false), "reopen the task")
                else -> {
                    completions.hold(task)
                    val result = taskRepository.toggleDone(task)
                    if (result is NetworkResult.Error) completions.release(task.id)
                    appMessages.postIfRefused(result, "complete the task")
                }
            }
        }
    }

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

    private val _refreshing = MutableStateFlow(false)

    /** A pull to refresh is running. */
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * Pull to refresh: the calendars are read again at once (contract 20.9), then tasks, projects and
     * labels in full and the day's own request, which brings its done blocks.
     */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                planCalendars.refreshNow()
                val day = selectedDay.first()
                val result = refresher.refresh(manual = true)
                taskRepository.refreshPlanDay(day)
                result.refreshErrorToShow(manual = true)?.let { appMessages.post(it) }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Whether Plan settings offer the phone's calendars (Android's provider, iOS EventKit). */
    val calendarsSupported: Boolean get() = planCalendars.supported

    /** How the screen phrases dates, for the messages written here. */
    var format: DateDisplayFormat = DateDisplayFormat.system(is24Hour = true)

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

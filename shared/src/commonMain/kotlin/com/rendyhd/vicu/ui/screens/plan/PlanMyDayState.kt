package com.rendyhd.vicu.ui.screens.plan

import com.rendyhd.vicu.ai.organize.PlanCanWait
import com.rendyhd.vicu.ai.organize.PlanDayRequest
import com.rendyhd.vicu.ai.organize.PlanDayResult
import com.rendyhd.vicu.ai.organize.PlanPick
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.BusyRange
import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.CalendarEvents
import com.rendyhd.vicu.util.DraftBlock
import com.rendyhd.vicu.util.DraftChoices
import com.rendyhd.vicu.util.LeftoverAction
import com.rendyhd.vicu.util.MinuteRange
import com.rendyhd.vicu.util.PlacedBlock
import com.rendyhd.vicu.util.PlacedEvent
import com.rendyhd.vicu.util.PlanDay
import com.rendyhd.vicu.util.PlanDraft
import com.rendyhd.vicu.util.PlanMeter
import com.rendyhd.vicu.util.PlannerHours
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.TimelineMarker
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

internal val PLAN_STEPS = listOf("Leftovers", "Choose", "Arrange", "Ready")

internal const val STEP_LEFTOVERS = 0
internal const val STEP_CHOOSE = 1
internal const val STEP_ARRANGE = 2
internal const val STEP_READY = 3

/** A leftover the person decided: it stays in the list, saying what happened. */
data class HandledLeftover(val task: Task, val label: String)

/** One row of the Leftovers step. */
data class LeftoverRow(val task: Task, val handled: String?, val action: LeftoverAction?, val reason: String?)

/** What the person chose so far. Nothing here is saved; the view model writes what is written at once. */
internal data class PlanChoices(
    val step: Int = STEP_LEFTOVERS,
    /** 0 is today, 1 is tomorrow. */
    val dayOffset: Int = 0,
    val assistantReady: Boolean = false,
    val assistantOn: Boolean = true,
    val byAssistant: Boolean = true,
    /** What is ticked. Until the person changes it, everything on the day. */
    val picked: Set<Long>? = null,
    val keep: Set<Long> = emptySet(),
    val pins: Map<Long, MinuteRange> = emptyMap(),
    val lengths: Map<Long, Int> = emptyMap(),
    val handled: Map<Long, HandledLeftover> = emptyMap(),
    val projectId: Long? = null,
    val remindersOn: Boolean = false,
    val accepting: Boolean = false,
) {
    val assistantActive: Boolean get() = assistantReady && assistantOn
}

/** The assistant's state. An answer and a failure each belong to the day they were asked for. */
internal data class PlanAnswers(
    val loading: Boolean = false,
    val answered: Pair<LocalDate, PlanDayResult>? = null,
    val failed: Pair<LocalDate, String>? = null,
)

/** The tasks and settings of a session, as stored now. */
internal data class PlanSessionData(
    val day: LocalDate,
    val today: LocalDate,
    val zone: TimeZone,
    /** The day's request: its blocks (done ones included), and what is due on it. */
    val dayTasks: List<Task>,
    val open: List<Task>,
    /** The Inbox's open tasks in the Inbox's order. */
    val inbox: List<Task>,
    /** The open tasks of the project picked for "From a project", in its order. */
    val picked: List<Task>,
    val projects: List<Project>,
    val collab: CollabState,
    val inboxId: Long?,
    val routines: List<TimelineRoutine>,
    val hours: PlannerHours,
    val defaultLength: Int,
    val nudge: String?,
    /** The day's events (contract 20): drawn on Arrange, and the busy ones of counting calendars are busy time. */
    val events: List<CalendarEvent> = emptyList(),
    /** The phone calendars that count as busy; your own Events always do. */
    val countingCalendars: Set<String> = emptySet(),
    /** Each calendar's colour, `#rrggbb`. */
    val eventColors: Map<String, String> = emptyMap(),
    /** The events are the ones read for [day]: the assistant's request waits for them. */
    val eventsRead: Boolean = true,
) {
    private val projectIds = projects.mapTo(HashSet()) { it.id }

    /** Busy time of the day's events (20.2): what the meter, the draft and the assistant's free time leave out. */
    val eventBusy: List<BusyRange>
        get() = CalendarEvents.eventBusyOfDay(events, day, zone, CalendarEvents.calendarCounts(countingCalendars))

    /** Tasks the person cannot change are left out of every step (contract 14.10). */
    fun canChange(task: Task): Boolean = task.projectId in projectIds && collab.access(task.projectId).canWrite
}

data class PlanMyDayUiState(
    val isLoading: Boolean = true,
    val step: Int = STEP_LEFTOVERS,
    val day: LocalDate? = null,
    val today: LocalDate? = null,
    val zone: TimeZone = TimeZone.UTC,
    val accepting: Boolean = false,

    val assistantReady: Boolean = false,
    val assistantOn: Boolean = true,
    val assistantActive: Boolean = false,
    val byAssistant: Boolean = true,
    val asking: Boolean = false,
    val failure: String? = null,
    val hasAnswer: Boolean = false,

    val leftoverRows: List<LeftoverRow> = emptyList(),
    /** Open leftovers with a suggested action, what "Apply the N suggestions" writes. */
    val suggestedLeftovers: List<Pair<Task, LeftoverAction>> = emptyList(),

    val onDay: List<Task> = emptyList(),
    val inbox: List<Task> = emptyList(),
    val comingUp: List<Task> = emptyList(),
    val fromProject: List<Task> = emptyList(),
    val projectId: Long? = null,
    val projectRows: List<PlanProjectRow> = emptyList(),
    val ticked: Set<Long> = emptySet(),
    val chosen: List<Task> = emptyList(),
    val meter: PlanMeter = PlanMeter(0, 0, 0, 0),
    val lengths: Map<Long, Int> = emptyMap(),
    /** The tasks whose length is still the assistant's. */
    val suggestedLengths: Set<Long> = emptySet(),
    val picks: Map<Long, PlanPick> = emptyMap(),
    /** Picks not ticked yet: the Suggested group. */
    val suggestedAdds: List<Pair<Task, PlanPick>> = emptyList(),
    /** What can wait, while over the free time. */
    val canWait: List<Pair<Task, PlanCanWait>> = emptyList(),

    val draft: PlanDraft = PlanDraft(emptyList(), emptyList()),
    val toPlace: List<Task> = emptyList(),
    val keep: Set<Long> = emptySet(),
    val hours: PlannerHours = TimeBlocks.DEFAULT_HOURS,
    val defaultLength: Int = TimeBlocks.DEFAULT_LENGTH_MINUTES,

    /** The timeline: on Arrange, the day's blocks with the draft laid over them. */
    val blocks: List<PlacedBlock> = emptyList(),
    /** The day's timed events beside the blocks, and its all-day ones. */
    val events: List<PlacedEvent> = emptyList(),
    val allDayEvents: List<CalendarEvent> = emptyList(),
    val eventColors: Map<String, String> = emptyMap(),
    val markers: List<TimelineMarker> = emptyList(),
    val routines: List<TimelineRoutine> = emptyList(),
    val dashedIds: Set<Long> = emptySet(),
    val reasons: Map<Long, String> = emptyMap(),

    val withTime: List<Task> = emptyList(),
    val withoutTime: List<Task> = emptyList(),
    val freeLeft: Int = 0,
    val remindersOn: Boolean = false,
    val nudge: String? = null,

    val projects: Map<Long, Project> = emptyMap(),
    val collab: CollabState = CollabState(),
) {
    val isToday: Boolean get() = day != null && day == today
    fun canChange(task: Task): Boolean = task.projectId in projects && collab.access(task.projectId).canWrite
    fun draftOf(id: Long): DraftBlock? = draft.placed.firstOrNull { it.id == id }
}

/** The session's lists from what is stored: every step reads these, and so does the request. */
internal data class PlanGroups(
    val leftovers: List<Task>,
    val onDay: List<Task>,
    val inbox: List<Task>,
    val comingUp: List<Task>,
)

internal fun planGroups(data: PlanSessionData, now: Instant): PlanGroups {
    val zone = data.zone
    val onDay = PlanDay.onDayTasks(data.dayTasks.filter(data::canChange), data.day, now, zone)
    val inbox = data.inboxId?.let { id -> PlanDay.inboxCandidates(data.inbox.filter(data::canChange), id, zone) }.orEmpty()
    return PlanGroups(
        leftovers = PlanDay.leftoversQueue(data.open.filter(data::canChange), now, zone),
        onDay = onDay,
        inbox = PlanDay.withoutTasks(inbox, onDay),
        comingUp = PlanDay.withoutTasks(PlanDay.comingUpTasks(data.open.filter(data::canChange), data.day, zone), onDay),
    )
}

/** The request for what [data] holds: candidates are the groups without a picked project, on the day first. */
internal fun planDayRequest(data: PlanSessionData, now: Instant): PlanDayRequest {
    val groups = planGroups(data, now)
    // Free ranges only: the assistant never gets an event's title (contract 20.2).
    val busy = TimeBlocks.busyOfDay(data.dayTasks, data.day, data.zone) + data.eventBusy
    return PlanDayRequest(
        day = data.day,
        nowMinute = TimeBlocks.minutesOfDay(now, data.zone),
        defaultLength = data.defaultLength,
        free = TimeBlocks.freeRanges(now, data.day, data.hours, busy, data.zone),
        routineMinutes = data.routines.map { it.minute },
        leftovers = groups.leftovers,
        candidates = groups.onDay + groups.inbox + groups.comingUp,
    )
}

internal fun planMyDayState(
    data: PlanSessionData,
    choices: PlanChoices,
    answers: PlanAnswers,
    now: Instant,
): PlanMyDayUiState {
    val day = data.day
    val zone = data.zone
    val groups = planGroups(data, now)
    val onDay = groups.onDay
    val inbox = groups.inbox
    val comingUp = groups.comingUp
    val fromProject = if (choices.projectId == null) {
        emptyList()
    } else {
        PlanDay.withoutTasks(PlanDay.withoutTasks(data.picked.filter { !it.done && data.canChange(it) }, onDay), comingUp)
            .take(PlanDay.PROJECT_GROUP_MAX)
    }

    val active = choices.assistantActive
    val answer = answers.answered?.takeIf { active && it.first == day }?.second
    val failure = answers.failed?.takeIf { active && it.first == day }?.second
    val picks = answer?.picks.orEmpty().associateBy { it.taskId }
    val leftoverSuggestions = answer?.leftovers.orEmpty().associateBy { it.taskId }

    val byId = HashMap<Long, Task>()
    for (list in listOf(fromProject, inbox, comingUp, groups.leftovers, onDay)) for (task in list) byId[task.id] = task

    val ticked = choices.picked ?: onDay.mapTo(LinkedHashSet()) { it.id }
    val chosen = ticked.mapNotNull { byId[it] }.filter { !it.done }

    fun lengthOf(id: Long): Int = choices.lengths[id] ?: picks[id]?.minutes ?: data.defaultLength
    val lengths = (chosen + inbox + comingUp + fromProject).associate { it.id to lengthOf(it.id) }
    val suggestedLengths = picks.values.filter { it.minutes != null && it.taskId !in choices.lengths }.mapTo(HashSet()) { it.taskId }

    val reasons = if (active) picks.values.associate { it.taskId to it.reason }.filterValues { it.isNotEmpty() } else emptyMap()
    val draftChoices = DraftChoices(
        lengths = chosen.associate { it.id to lengthOf(it.id) },
        pins = choices.pins,
        keep = choices.keep,
        starts = if (answer != null && choices.byAssistant) {
            answer.picks.mapNotNull { pick -> pick.start?.let { pick.taskId to TimeBlocks.clockMinutes(it) } }
        } else {
            emptyList()
        },
        reasons = reasons,
    )
    val eventBusy = data.eventBusy
    val meter = PlanDay.planMeter(chosen, data.dayTasks, now, day, data.hours, data.defaultLength, zone, draftChoices, eventBusy)
    val draft = PlanDay.draftBlocks(chosen, data.dayTasks, now, day, data.hours, data.defaultLength, zone, draftChoices, eventBusy)

    val arranging = choices.step == STEP_ARRANGE
    val timeline = if (arranging) overlaid(data.dayTasks, draft, byId, day, zone) else data.dayTasks
    val layout = PlannerTimeline.layoutWithEvents(
        PlannerTimeline.dayBlocks(timeline, day, zone),
        CalendarEvents.timedEventsOn(data.events, day, zone),
    )
    val candidateIds = (onDay + inbox + comingUp).mapTo(HashSet()) { it.id }

    return PlanMyDayUiState(
        isLoading = false,
        step = choices.step,
        day = day,
        today = data.today,
        zone = zone,
        accepting = choices.accepting,
        assistantReady = choices.assistantReady,
        assistantOn = choices.assistantOn,
        assistantActive = active,
        byAssistant = choices.byAssistant,
        asking = active && answers.loading,
        failure = failure,
        hasAnswer = answer != null,
        leftoverRows = leftoverRows(groups.leftovers, choices.handled, leftoverSuggestions.mapValues { it.value.action to it.value.reason }),
        suggestedLeftovers = groups.leftovers.mapNotNull { task ->
            leftoverSuggestions[task.id]?.takeIf { task.id !in choices.handled }?.let { task to it.action }
        },
        onDay = onDay,
        inbox = inbox,
        comingUp = comingUp,
        fromProject = fromProject,
        projectId = choices.projectId,
        projectRows = PlanViewModel.projectRows(data.projects, data.inboxId),
        ticked = ticked,
        chosen = chosen,
        meter = meter,
        lengths = lengths,
        suggestedLengths = suggestedLengths,
        picks = picks,
        suggestedAdds = picks.values
            .filter { it.taskId !in ticked && it.taskId in candidateIds }
            .take(MAX_SUGGESTED_ADDS)
            .mapNotNull { pick -> byId[pick.taskId]?.let { it to pick } },
        canWait = answer?.canWait.orEmpty()
            .filter { it.taskId in ticked }
            .mapNotNull { item -> byId[item.taskId]?.let { it to item } },
        draft = draft,
        toPlace = PlanDay.tasksToPlace(chosen, day, zone),
        keep = choices.keep,
        hours = data.hours,
        defaultLength = data.defaultLength,
        blocks = layout.blocks,
        events = layout.events,
        allDayEvents = CalendarEvents.allDayEventsOn(data.events, day, zone),
        eventColors = data.eventColors,
        markers = PlannerTimeline.dayMarkers(timeline, day, zone),
        routines = data.routines,
        dashedIds = if (arranging) draft.placed.mapTo(HashSet()) { it.id } else emptySet(),
        reasons = reasons,
        withTime = data.dayTasks.filter { !it.done && TimeBlocks.startsOn(it, day, zone) }
            .sortedBy { TimeBlocks.blockOf(it, zone)?.start },
        withoutTime = onDay.filter { !TimeBlocks.startsOn(it, day, zone) },
        freeLeft = TimeBlocks.freeMinutes(now, day, data.hours, TimeBlocks.busyOfDay(data.dayTasks, day, zone) + eventBusy, zone),
        remindersOn = choices.remindersOn,
        nudge = data.nudge,
        projects = data.projects.associateBy { it.id },
        collab = data.collab,
    )
}

private const val MAX_SUGGESTED_ADDS = 3

private fun leftoverRows(
    leftovers: List<Task>,
    handled: Map<Long, HandledLeftover>,
    suggestions: Map<Long, Pair<LeftoverAction, String>>,
): List<LeftoverRow> {
    val present = leftovers.mapTo(HashSet()) { it.id }
    val rows = leftovers.map { task ->
        val label = handled[task.id]?.label
        val suggestion = suggestions[task.id]?.takeIf { label == null }
        LeftoverRow(task, label, suggestion?.first, suggestion?.second)
    }
    // An action takes a task off the queue; its row stays and says what happened.
    return rows + handled.filterKeys { it !in present }.values.map { LeftoverRow(it.task, it.label, null, null) }
}

/** The tasks the timeline draws: the day's, with the draft's blocks laid over them. */
private fun overlaid(
    dayTasks: List<Task>,
    draft: PlanDraft,
    byId: Map<Long, Task>,
    day: LocalDate,
    zone: TimeZone,
): List<Task> {
    val drafts = draft.placed.associateBy { it.id }
    fun Task.withDraft(block: DraftBlock) = copy(
        startDate = TimeBlocks.atClock(day, block.start, zone).toString(),
        endDate = TimeBlocks.atClock(day, block.end, zone).toString(),
    )
    val tasks = dayTasks.map { task -> drafts[task.id]?.let { task.withDraft(it) } ?: task }
    val present = tasks.mapTo(HashSet()) { it.id }
    return tasks + draft.placed.filter { it.id !in present }.mapNotNull { block -> byId[block.id]?.withDraft(block) }
}

/** The name of a session's day, in a sentence's start. */
internal fun sessionDayName(day: LocalDate, today: LocalDate): String =
    if (day == today) "Today" else "Tomorrow"

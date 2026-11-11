package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** What a session does with a leftover (contract 14.10). [id] is how the assistant names it. */
enum class LeftoverAction(val id: String) {
    TODAY("today"),
    TOMORROW("tomorrow"),
    NO_DATE("no_date"),
    DONE("done"),
    ;

    companion object {
        fun of(id: String?): LeftoverAction? = entries.firstOrNull { it.id == id }
    }
}

/** What a session's draft is laid out from. It is laid out again whenever one changes, with no new request. */
data class DraftChoices(
    /** A length the person or the assistant gave, by task. */
    val lengths: Map<Long, Int> = emptyMap(),
    /** Placed by hand, by task. */
    val pins: Map<Long, MinuteRange> = emptyMap(),
    /** Kept without a time. */
    val keep: Set<Long> = emptySet(),
    /** The assistant's order: its starts, tried first. */
    val starts: List<Pair<Long, Int>> = emptyList(),
    /** Why a block sits there, by task. */
    val reasons: Map<Long, String> = emptyMap(),
)

/** The meter of Choose: how much is ticked against the day's free time. */
data class PlanMeter(val tasks: Int, val toPlace: Int, val free: Int, val over: Int)

/** A block of the draft, in minutes from the day's local midnight. */
data class DraftBlock(val id: Long, val start: Int, val end: Int, val pinned: Boolean, val reason: String? = null)

/** The draft: its blocks, and the tasks with no free slot left. */
data class PlanDraft(val placed: List<DraftBlock>, val noSlot: List<Long>)

/**
 * Plan my day (docs/cross-app-semantics-v1.md section 14.10), the same as the desktop's
 * `src/shared/plan-day.ts`: a session's queue and groups, the meter, the draft and what accepting it
 * writes. Pure: the clock and the zone are passed in, and the view model runs the writes.
 */
object PlanDay {
    /** At most this many tasks of a picked project. */
    const val PROJECT_GROUP_MAX = 6

    /** What a tap on a length goes through. */
    val LENGTH_CYCLE: List<Int> = listOf(15, 30, 45, 60, 90, 120)

    /** How far Coming up looks past the day. */
    private const val COMING_UP_DAYS = 3

    // --- Leftovers -------------------------------------------------------------------------------

    /** The moment that makes a task a leftover, or null when it is not one. */
    private fun leftSince(task: Task, now: Instant, zone: TimeZone): Instant? {
        if (task.done) return null
        val today = now.toLocalDateTime(zone).date
        val fromBlock = TimeBlocks.blockOf(task, zone)?.takeIf { it.day < today }?.start
        val fromDue = if (DueDates.isOverdue(task.dueDate, today, zone)) DateUtils.parseIsoDate(task.dueDate) else null
        return listOfNotNull(fromBlock, fromDue).minOrNull()
    }

    /** Open tasks due before today or with a block on a day before today, oldest first. */
    fun leftoversQueue(tasks: List<Task>, now: Instant, zone: TimeZone): List<Task> =
        tasks.mapNotNull { task -> leftSince(task, now, zone)?.let { task to it } }
            .sortedWith(compareBy<Pair<Task, Instant>> { it.second }.thenBy { it.first.id })
            .map { it.first }

    /**
     * What an action on a leftover writes, or null for Done, which is the completion and nothing else.
     * Its old block does not follow the date: that time has passed.
     */
    fun leftoverChanges(task: Task, action: LeftoverAction, now: Instant, zone: TimeZone): BlockChanges? {
        val today = now.toLocalDateTime(zone).date
        return when (action) {
            LeftoverAction.TODAY -> TimeBlocks.planForDay(task.reminders, today, zone)
            LeftoverAction.TOMORROW -> TimeBlocks.planForDay(task.reminders, today.plus(1, DateTimeUnit.DAY), zone)
            LeftoverAction.NO_DATE -> TimeBlocks.removeTime(task.reminders).copy(dueDate = TimeBlocks.CLEARED)
            LeftoverAction.DONE -> null
        }
    }

    // --- Choose ----------------------------------------------------------------------------------

    /** On the day: due on it or with a block on it, and on today the overdue tasks, in Fit order. */
    fun onDayTasks(tasks: List<Task>, day: LocalDate, now: Instant, zone: TimeZone): List<Task> {
        val today = now.toLocalDateTime(zone).date
        return TimeBlocks.fitOrder(
            tasks.filter { task ->
                when {
                    task.done -> false
                    TimeBlocks.startsOn(task, day, zone) -> true
                    else -> {
                        val due = DueDates.localDateOf(task.dueDate, zone)
                        due != null && (due == day || (day == today && DueDates.isOverdue(task.dueDate, today, zone)))
                    }
                }
            },
            today,
            zone,
        )
    }

    /** The Inbox's tasks without a due date, in the order given (the Inbox's own). */
    fun inboxCandidates(tasks: List<Task>, inboxId: Long, zone: TimeZone): List<Task> =
        tasks.filter { !it.done && it.projectId == inboxId && DueDates.localDateOf(it.dueDate, zone) == null }

    /** Due one to three days after the day, without a block on it, by due date, priority, id. */
    fun comingUpTasks(tasks: List<Task>, day: LocalDate, zone: TimeZone): List<Task> {
        val last = day.plus(COMING_UP_DAYS, DateTimeUnit.DAY)
        return tasks
            .filter { task ->
                if (task.done || TimeBlocks.startsOn(task, day, zone)) return@filter false
                val due = DueDates.localDateOf(task.dueDate, zone) ?: return@filter false
                due > day && due <= last
            }
            .sortedWith(
                compareBy<Task> { DateUtils.parseIsoDate(it.dueDate) }
                    .thenByDescending { it.priority }
                    .thenBy { it.id },
            )
    }

    /** Groups hold no task twice: each leaves out what an earlier one took. */
    fun withoutTasks(tasks: List<Task>, taken: List<Task>): List<Task> {
        val ids = taken.mapTo(HashSet()) { it.id }
        return tasks.filter { it.id !in ids }
    }

    /** The ticked tasks that need a time: no block on the day, not kept without one. */
    fun tasksToPlace(ticked: List<Task>, day: LocalDate, zone: TimeZone, keep: Set<Long> = emptySet()): List<Task> =
        ticked.filter { !TimeBlocks.startsOn(it, day, zone) && it.id !in keep }

    /** The meter: a task placed by hand counts its own length. */
    fun planMeter(
        ticked: List<Task>,
        dayTasks: List<Task>,
        now: Instant,
        day: LocalDate,
        hours: PlannerHours,
        defaultLength: Int,
        zone: TimeZone,
        choices: DraftChoices = DraftChoices(),
        /** Busy time of the day's events that count (contract 20.2). */
        eventBusy: List<BusyRange> = emptyList(),
    ): PlanMeter {
        val free = TimeBlocks.freeMinutes(now, day, hours, TimeBlocks.busyOfDay(dayTasks, day, zone) + eventBusy, zone)
        val toPlace = tasksToPlace(ticked, day, zone, choices.keep).sumOf { task ->
            choices.pins[task.id]?.let { it.end - it.start } ?: choices.lengths[task.id] ?: defaultLength
        }
        return PlanMeter(ticked.size, toPlace, free, maxOf(0, toPlace - free))
    }

    // --- Arrange ---------------------------------------------------------------------------------

    /**
     * The draft: blocks placed by hand first, counted as busy; then the assistant's starts that still
     * fit, in its order; then the rest take the next free slot in Fit order.
     */
    fun draftBlocks(
        ticked: List<Task>,
        dayTasks: List<Task>,
        now: Instant,
        day: LocalDate,
        hours: PlannerHours,
        defaultLength: Int,
        zone: TimeZone,
        choices: DraftChoices = DraftChoices(),
        /** Busy time of the day's events that count (contract 20.2). */
        eventBusy: List<BusyRange> = emptyList(),
    ): PlanDraft {
        val place = tasksToPlace(ticked, day, zone, choices.keep)
        val lengthOf = { task: Task -> choices.lengths[task.id] ?: defaultLength }
        val busy = (TimeBlocks.busyOfDay(dayTasks, day, zone) + eventBusy).toMutableList()
        val placed = mutableListOf<DraftBlock>()
        val settled = HashSet<Long>()

        for (task in place) {
            val pin = choices.pins[task.id] ?: continue
            placed += DraftBlock(task.id, pin.start, pin.end, pinned = true, reason = choices.reasons[task.id])
            busy += pin
            settled += task.id
        }

        for ((id, start) in choices.starts) {
            val task = place.firstOrNull { it.id == id } ?: continue
            if (id in settled) continue
            val end = start + lengthOf(task)
            if (!fitsFreeTime(now, day, hours, busy, start, end, zone)) continue
            placed += DraftBlock(id, start, end, pinned = false, reason = choices.reasons[id])
            busy += BusyRange(start, end)
            settled += id
        }

        val rest = place.filter { it.id !in settled }
        val fitted = TimeBlocks.fitTasks(rest, now, day, hours, busy, zone, lengthOf)
        for (fit in fitted) {
            placed += DraftBlock(fit.task.id, fit.start, fit.end, pinned = false, reason = choices.reasons[fit.task.id])
        }
        val fittedIds = fitted.mapTo(HashSet()) { it.task.id }
        val today = now.toLocalDateTime(zone).date
        return PlanDraft(placed, TimeBlocks.fitOrder(rest, today, zone).map { it.id }.filter { it !in fittedIds })
    }

    /** Inside one free range of the day, with [busy] taken. */
    fun fitsFreeTime(
        now: Instant,
        day: LocalDate,
        hours: PlannerHours,
        busy: List<BusyRange>,
        start: Int,
        end: Int,
        zone: TimeZone,
    ): Boolean = TimeBlocks.freeRanges(now, day, hours, busy, zone).any { start >= it.start && end <= it.end }

    /** What accepting the plan writes for a task, or null for nothing. */
    fun acceptChanges(task: Task, day: LocalDate, draft: DraftBlock?, zone: TimeZone): BlockChanges? {
        if (draft != null) {
            return TimeBlocks.placeBlock(task.dueDate, TimeBlocks.atClock(day, draft.start, zone), draft.end - draft.start, zone)
        }
        if (TimeBlocks.startsOn(task, day, zone)) return null
        if (DueDates.localDateOf(task.dueDate, zone) == day) return null
        return TimeBlocks.planForDay(task.reminders, day, zone)
    }

    /** Moving a task that can wait: the next day, date-only, and no time. */
    fun canWaitChanges(task: Task, day: LocalDate, zone: TimeZone): BlockChanges =
        TimeBlocks.planForDay(task.reminders, day.plus(1, DateTimeUnit.DAY), zone)

    // --- Ready -----------------------------------------------------------------------------------

    /** A due time on [day]: Ready lists the task by it, because that is no "any time". */
    fun dueTimeOn(task: Task, day: LocalDate, zone: TimeZone): LocalTime? {
        val due = DateUtils.parseIsoDate(task.dueDate) ?: return null
        if (DueDates.isDateOnly(due, zone) || DueDates.localDateOf(due, zone) != day) return null
        return due.toLocalDateTime(zone).time
    }

    private fun isStartReminder(reminder: TaskReminder): Boolean =
        reminder.relativeTo == "start_date" && reminder.relativePeriod == 0L

    /** Remind me when the block starts: added once, and the others stay. [start] is the block's, for the alarm. */
    fun withStartReminder(reminders: List<TaskReminder>, start: String = ""): List<TaskReminder> =
        if (reminders.any(::isStartReminder)) {
            reminders
        } else {
            reminders + TaskReminder(reminder = start, relativePeriod = 0, relativeTo = "start_date")
        }

    fun withoutStartReminder(reminders: List<TaskReminder>): List<TaskReminder> =
        reminders.filterNot(::isStartReminder)
}

/**
 * What a session changed, for Undo all: how each task was the first time it was touched, and which
 * fields it wrote. Undo all writes those back and reopens what it completed; it deletes nothing.
 */
class PlanSessionLog {
    data class Entry(
        val before: Task,
        val due: Boolean = false,
        val start: Boolean = false,
        val end: Boolean = false,
        val reminders: Boolean = false,
        val completed: Boolean = false,
    ) {
        /** [current] with what the session wrote put back. Completing a repeating task moves all of these. */
        fun undoneOn(current: Task): Task = current.copy(
            dueDate = if (due || completed) before.dueDate else current.dueDate,
            startDate = if (start || completed) before.startDate else current.startDate,
            endDate = if (end || completed) before.endDate else current.endDate,
            reminders = if (reminders || completed) before.reminders else current.reminders,
        )
    }

    private val byTask = LinkedHashMap<Long, Entry>()

    val entries: List<Entry> get() = byTask.values.toList()

    fun changed(before: Task, changes: BlockChanges) {
        val entry = byTask[before.id] ?: Entry(before)
        byTask[before.id] = entry.copy(
            due = entry.due || changes.dueDate != null,
            start = entry.start || changes.startDate != null,
            end = entry.end || changes.endDate != null,
            reminders = entry.reminders || changes.reminders != null,
        )
    }

    fun completed(before: Task) {
        byTask[before.id] = (byTask[before.id] ?: Entry(before)).copy(completed = true)
    }

    fun clear() = byTask.clear()
}

package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A task's block (section 16.1). [day] is the local date of the start. */
data class TimeBlock(
    val start: Instant,
    val end: Instant,
    val day: LocalDate,
    val minutes: Int,
)

/** The work hours, `"HH:mm"` in 24-hour local time, the start before the end (section 11.3). */
data class PlannerHours(val start: String, val end: String)

/** A busy stretch in minutes from the local midnight of the day. */
data class BusyRange(val start: Int, val end: Int)

/** Any stretch of a day in minutes from its local midnight: free time, a block placed by hand. */
typealias MinuteRange = BusyRange

/** A task placed by Fit, in minutes from the local midnight of the day. */
data class FittedTask(val task: Task, val start: Int, val end: Int)

/** Overdue, then the Today section (section 2). */
data class TodaySplit(val overdue: List<Task>, val today: List<Task>)

enum class PlanViewKind { DAY, THREE_DAYS, WEEK }

/**
 * What a block action writes; a null field stays as it is, and [TimeBlocks.CLEARED] empties a date,
 * which the merge patch sends as null. Build the update with [applyTo] on the stored task.
 */
data class BlockChanges(
    val startDate: String? = null,
    val endDate: String? = null,
    val dueDate: String? = null,
    val reminders: List<TaskReminder>? = null,
) {
    fun applyTo(task: Task): Task = task.copy(
        startDate = startDate ?: task.startDate,
        endDate = endDate ?: task.endDate,
        dueDate = dueDate ?: task.dueDate,
        reminders = reminders ?: task.reminders,
    )
}

/**
 * Time blocks (docs/cross-app-semantics-v1.md section 16), the same as the desktop's
 * `src/shared/time-blocks.ts`. A block is the task's own start_date and end_date; anything else in
 * those fields is not a block and is left alone. Pure: the day, the time and the zone are passed in.
 */
object TimeBlocks {
    /** A block is at most 12 hours. */
    const val MAX_BLOCK_MINUTES = 720

    /** Gestures work in 15-minute steps, and a dragged block is at least one step. */
    const val STEP_MINUTES = 15
    const val DEFAULT_LENGTH_MINUTES = 30

    /** The lengths `planner.default_length` may hold (section 11.3). */
    val BLOCK_LENGTHS: List<Int> = listOf(15, 30, 45, 60, 75, 90)
    val DEFAULT_HOURS = PlannerHours("08:00", "18:00")

    /** How a block action empties a date. */
    const val CLEARED: String = Constants.NULL_DATE_STRING

    private const val DAY_MINUTES = 24 * 60

    // --- What a block is (16.1) ------------------------------------------------------------------

    /**
     * Both set, the end after the start, at most 12 hours apart, and the end no later than the
     * midnight that ends the start's day in [zone].
     */
    fun isBlock(start: Instant?, end: Instant?, zone: TimeZone): Boolean {
        if (start == null || end == null) return false
        val length = end - start
        if (length <= Duration.ZERO || length > MAX_BLOCK_MINUTES.minutes) return false
        return end <= nextMidnight(start, zone)
    }

    /** The same for stored values; a blank or null date is no date. */
    fun isBlock(start: String?, end: String?, zone: TimeZone): Boolean =
        isBlock(instantOf(start), instantOf(end), zone)

    private fun instantOf(value: String?): Instant? = DateUtils.parseIsoDate(value?.takeIf { it.isNotBlank() })

    private fun nextMidnight(value: Instant, zone: TimeZone): Instant =
        value.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)

    /** The block of two stored dates, or null when they are not one. */
    fun blockOf(start: String?, end: String?, zone: TimeZone): TimeBlock? {
        val from = instantOf(start) ?: return null
        val to = instantOf(end) ?: return null
        if (!isBlock(from, to, zone)) return null
        return TimeBlock(
            start = from,
            end = to,
            day = from.toLocalDateTime(zone).date,
            minutes = ((to - from).inWholeSeconds / 60.0).roundToInt(),
        )
    }

    fun blockOf(task: Task, zone: TimeZone): TimeBlock? = blockOf(task.startDate, task.endDate, zone)

    fun startsOn(task: Task, day: LocalDate, zone: TimeZone): Boolean = blockOf(task, zone)?.day == day

    /**
     * A date-only due date on the block's own day says nothing the block does not (section 16.4), so
     * a row leaves it out. An overdue one stays: it is a warning, not a day phrase.
     */
    fun dueRepeatsBlockDay(due: String?, block: TimeBlock, today: LocalDate, zone: TimeZone): Boolean =
        DueDates.isDateOnly(due, zone) &&
            DueDates.localDateOf(due, zone) == block.day &&
            !DueDates.isOverdue(due, today, zone)

    // --- The Today list (sections 2 and 16.4) ------------------------------------------------------

    /** Whether a task with these stored dates is on the Today list: overdue, due today, or a block starting today. */
    fun isOnToday(due: String?, start: String?, end: String?, today: LocalDate, zone: TimeZone): Boolean =
        when (DueDates.bucket(due, today, zone)) {
            DueDates.Bucket.OVERDUE, DueDates.Bucket.TODAY -> true
            else -> blockOf(start, end, zone)?.day == today
        }

    fun isOnToday(task: Task, today: LocalDate, zone: TimeZone): Boolean =
        isOnToday(task.dueDate, task.startDate, task.endDate, today, zone)

    /**
     * Overdue, then the Today section: due today, or a block starting today. A task due earlier with a
     * block today stays in Overdue. Upcoming, custom lists and the guided queues read the due date only.
     */
    fun splitToday(tasks: List<Task>, today: LocalDate, zone: TimeZone): TodaySplit {
        val overdue = mutableListOf<Task>()
        val section = mutableListOf<Task>()
        for (task in tasks) {
            when {
                DueDates.isOverdue(task.dueDate, today, zone) -> overdue += task
                isOnToday(task, today, zone) -> section += task
            }
        }
        return TodaySplit(overdue, section)
    }

    // --- Writing a block (16.2) --------------------------------------------------------------------

    /** A wall-clock time on a local date. Past a midnight it rolls to the next day. */
    fun atClock(day: LocalDate, minutes: Int, zone: TimeZone): Instant {
        val date = day.plus(minutes.floorDiv(DAY_MINUTES), DateTimeUnit.DAY)
        val rest = minutes.mod(DAY_MINUTES)
        return LocalDateTime(date, LocalTime(rest / 60, rest % 60)).toInstant(zone)
    }

    fun minutesOfDay(value: Instant, zone: TimeZone): Int {
        val time = value.toLocalDateTime(zone).time
        return time.hour * 60 + time.minute
    }

    /** The due rule: no due date or one before the start becomes that day, date-only. A later one is a deadline. */
    fun dueForBlock(due: String?, start: Instant, zone: TimeZone): String {
        val current = instantOf(due)
        if (current == null || current < start) {
            return DueDates.dateOnlyDue(start.toLocalDateTime(zone).date, zone).toString()
        }
        return due!!
    }

    /** The end of a block from its start and length: held at the midnight that ends its day. */
    fun blockEnd(start: Instant, minutes: Int, zone: TimeZone): Instant =
        minOf(start + minutes.minutes, nextMidnight(start, zone))

    /** Drop a task on a time, or move a block: start, end and the due rule. */
    fun placeBlock(due: String?, start: Instant, minutes: Int, zone: TimeZone): BlockChanges = BlockChanges(
        startDate = start.toString(),
        endDate = blockEnd(start, minutes, zone).toString(),
        dueDate = dueForBlock(due, start, zone),
    )

    /** Drag the bottom edge to [end]: only the end changes, kept to at least one step and within 16.1. */
    fun resizeEnd(block: TimeBlock, end: Instant, zone: TimeZone): BlockChanges {
        val earliest = block.start + STEP_MINUTES.minutes
        val latest = minOf(block.start + MAX_BLOCK_MINUTES.minutes, nextMidnight(block.start, zone))
        return BlockChanges(endDate = maxOf(earliest, minOf(latest, end)).toString())
    }

    /** Drag the top edge to [start]: the start and the due rule; the end stays. */
    fun resizeStart(due: String?, block: TimeBlock, start: Instant, zone: TimeZone): BlockChanges {
        val latest = block.end - STEP_MINUTES.minutes
        val dayStart = (block.end - 1.milliseconds).toLocalDateTime(zone).date.atStartOfDayIn(zone)
        val earliest = maxOf(dayStart, block.end - MAX_BLOCK_MINUTES.minutes)
        val next = maxOf(earliest, minOf(latest, start))
        return BlockChanges(startDate = next.toString(), dueDate = dueForBlock(due, next, zone))
    }

    /** Reminders that are not relative to the block: the ones that outlive removing its time. */
    fun remindersWithoutBlock(reminders: List<TaskReminder>): List<TaskReminder> =
        reminders.filter { it.relativeTo != "start_date" && it.relativeTo != "end_date" }

    /** Remove the time: both dates null, and any reminder relative to either. The due date stays. */
    fun removeTime(reminders: List<TaskReminder>): BlockChanges {
        val kept = remindersWithoutBlock(reminders)
        return BlockChanges(
            startDate = CLEARED,
            endDate = CLEARED,
            reminders = kept.takeIf { it.size != reminders.size },
        )
    }

    /**
     * Move a block to another [day] (a day picker, or a day in the strip): the same wall-clock start
     * and end, held at the midnight that ends the day, and the due rule. Wall-clock, as the desktop's
     * `moveBlockToDay`: a block across a clock change keeps the times it shows.
     */
    fun moveToDay(due: String?, block: TimeBlock, day: LocalDate, zone: TimeZone): BlockChanges {
        val from = minutesOfDay(block.start, zone)
        val start = atClock(day, from, zone)
        val end = atClock(day, minOf(from + wallClockMinutes(block, zone), DAY_MINUTES), zone)
        return BlockChanges(startDate = start.toString(), endDate = end.toString(), dueDate = dueForBlock(due, start, zone))
    }

    /** Drop a task on a day without a time. */
    fun planForDay(reminders: List<TaskReminder>, day: LocalDate, zone: TimeZone): BlockChanges =
        removeTime(reminders).copy(dueDate = DueDates.dateOnlyDue(day, zone).toString())

    // --- When the due date changes (16.3) ----------------------------------------------------------

    /**
     * What else a change of the due date writes, or null. A block on the day of the old due date
     * follows it at the same wall-clock times, and goes when the new date is before today or cleared.
     * A block on any other day stays, and so does one when only the due date's time changes.
     */
    fun blockChangesForDue(task: Task, newDue: String?, today: LocalDate, zone: TimeZone): BlockChanges? {
        val block = blockOf(task, zone) ?: return null
        val oldDay = DueDates.localDateOf(task.dueDate, zone) ?: return null
        if (oldDay != block.day) return null
        val newDay = DueDates.localDateOf(newDue, zone) ?: return removeTime(task.reminders)
        if (newDay < today) return removeTime(task.reminders)
        if (newDay == block.day) return null
        val days = block.day.daysUntil(newDay)
        fun moved(value: Instant): String {
            val local = value.toLocalDateTime(zone)
            return LocalDateTime(local.date.plus(days, DateTimeUnit.DAY), local.time).toInstant(zone).toString()
        }
        return BlockChanges(startDate = moved(block.start), endDate = moved(block.end))
    }

    /**
     * [after] with the rule above applied, for anything that writes a task: [before] is the task as it
     * is stored. A change that writes the block itself (the Plan screen, `for 30m` in the title
     * editor, an Undo) already decided, so it is left as it is.
     */
    fun withDueChangeRules(before: Task, after: Task, today: LocalDate, zone: TimeZone): Task {
        if (sameDate(before.dueDate, after.dueDate)) return after
        if (!sameDate(before.startDate, after.startDate) || !sameDate(before.endDate, after.endDate)) return after
        val extra = blockChangesForDue(after.copy(dueDate = before.dueDate), after.dueDate, today, zone) ?: return after
        return extra.applyTo(after)
    }

    private fun sameDate(a: String, b: String): Boolean = a == b || instantOf(a) == instantOf(b)

    // --- Free time and fitting (16.6) --------------------------------------------------------------

    fun clockMinutes(value: String): Int {
        val (h, m) = value.split(':').map { it.toInt() }
        return h * 60 + m
    }

    fun clockText(minutes: Int): String =
        "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

    /** The stretch of a day the work hours leave: today from now on. Null when nothing is left. */
    private fun workWindow(now: Instant, day: LocalDate, hours: PlannerHours, zone: TimeZone): IntRange? {
        val today = now.toLocalDateTime(zone).date
        if (day < today) return null
        val to = clockMinutes(hours.end)
        val from = if (day == today) maxOf(clockMinutes(hours.start), minutesOfDay(now, zone)) else clockMinutes(hours.start)
        return if (from < to) from..to else null
    }

    private fun mergedBusy(busy: List<BusyRange>): List<BusyRange> {
        val merged = mutableListOf<BusyRange>()
        for (range in busy.filter { it.end > it.start }.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && range.start <= last.end) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, range.end))
            } else {
                merged += range
            }
        }
        return merged
    }

    /** The work hours (today, from now) minus busy time, with overlaps counted once. */
    fun freeMinutes(now: Instant, day: LocalDate, hours: PlannerHours, busy: List<BusyRange>, zone: TimeZone): Int {
        val window = workWindow(now, day, hours, zone) ?: return 0
        var free = window.last - window.first
        for (range in mergedBusy(busy)) {
            val from = maxOf(range.start, window.first)
            val to = minOf(range.end, window.last)
            if (to > from) free -= to - from
        }
        return free
    }

    /** The free stretches of a day: the work hours (today, from now) less busy time. */
    fun freeRanges(now: Instant, day: LocalDate, hours: PlannerHours, busy: List<BusyRange>, zone: TimeZone): List<MinuteRange> {
        val window = workWindow(now, day, hours, zone) ?: return emptyList()
        val ranges = mutableListOf<MinuteRange>()
        var at = window.first
        for (range in mergedBusy(busy)) {
            if (range.end <= at) continue
            if (range.start >= window.last) break
            if (range.start > at) ranges += MinuteRange(at, range.start)
            at = maxOf(at, range.end)
        }
        if (at < window.last) ranges += MinuteRange(at, window.last)
        return ranges
    }

    private fun nextLine(minutes: Int): Int = ceil(minutes.toDouble() / STEP_MINUTES).toInt() * STEP_MINUTES

    /**
     * The first 15-minute line from the start of the work hours (today, from now) where [minutes]
     * fits without touching busy time and ends inside the work hours. Null when there is none.
     */
    fun nextFreeSlot(
        now: Instant,
        day: LocalDate,
        hours: PlannerHours,
        minutes: Int,
        busy: List<BusyRange>,
        zone: TimeZone,
    ): Int? {
        val window = workWindow(now, day, hours, zone) ?: return null
        val ranges = mergedBusy(busy)
        var at = nextLine(window.first)
        while (true) {
            if (at + minutes > window.last) return null
            val hit = ranges.firstOrNull { at < it.end && at + minutes > it.start } ?: return at
            at = nextLine(hit.end)
        }
    }

    /** Overdue first, then priority from high to low, then due date from earliest, no due date last, then id. */
    fun fitOrder(tasks: List<Task>, today: LocalDate, zone: TimeZone): List<Task> = tasks.sortedWith(
        compareBy<Task> { !DueDates.isOverdue(it.dueDate, today, zone) }
            .thenByDescending { it.priority }
            .thenBy { instantOf(it.dueDate) ?: Instant.DISTANT_FUTURE }
            .thenBy { it.id },
    )

    /** Each task in [fitOrder] takes the next free slot at [lengthOf]. What does not fit is left out. */
    fun fitTasks(
        tasks: List<Task>,
        now: Instant,
        day: LocalDate,
        hours: PlannerHours,
        busy: List<BusyRange>,
        zone: TimeZone,
        lengthOf: (Task) -> Int,
    ): List<FittedTask> {
        val taken = busy.toMutableList()
        val fitted = mutableListOf<FittedTask>()
        for (task in fitOrder(tasks, now.toLocalDateTime(zone).date, zone)) {
            val length = lengthOf(task)
            val start = nextFreeSlot(now, day, hours, length, taken, zone) ?: continue
            fitted += FittedTask(task, start, start + length)
            taken += BusyRange(start, start + length)
        }
        return fitted
    }

    /** The busy ranges a day's open blocks make. Routines and due-time markers have no length and are never busy. */
    fun busyOfDay(tasks: List<Task>, day: LocalDate, zone: TimeZone): List<BusyRange> = tasks.mapNotNull { task ->
        if (task.done) return@mapNotNull null
        val block = blockOf(task, zone)?.takeIf { it.day == day } ?: return@mapNotNull null
        val start = minutesOfDay(block.start, zone)
        BusyRange(start, start + wallClockMinutes(block, zone))
    }

    /** Wall-clock length, so a block across a clock change still fits the day's grid. */
    private fun wallClockMinutes(block: TimeBlock, zone: TimeZone): Int =
        if (block.end == nextMidnight(block.start, zone)) {
            DAY_MINUTES - minutesOfDay(block.start, zone)
        } else {
            minutesOfDay(block.end, zone) - minutesOfDay(block.start, zone)
        }

    /** The days of a view: one, three from the selected day, or the week holding it (Monday-start, or by [weekStart]). */
    fun viewDays(view: PlanViewKind, day: LocalDate, weekStart: WeekStart = WeekStart.DEFAULT): List<LocalDate> = when (view) {
        PlanViewKind.DAY -> listOf(day)
        PlanViewKind.THREE_DAYS -> List(3) { day.plus(it, DateTimeUnit.DAY) }
        PlanViewKind.WEEK -> DueDates.startOfWeek(day, weekStart).let { first -> List(7) { first.plus(it, DateTimeUnit.DAY) } }
    }

    // --- The two preferences (11.3) ----------------------------------------------------------------

    fun readDefaultLength(value: JsonElement?): Int {
        val primitive = value as? JsonPrimitive ?: return DEFAULT_LENGTH_MINUTES
        if (primitive.isString) return DEFAULT_LENGTH_MINUTES
        val number = primitive.doubleOrNull ?: return DEFAULT_LENGTH_MINUTES
        return BLOCK_LENGTHS.firstOrNull { it.toDouble() == number } ?: DEFAULT_LENGTH_MINUTES
    }

    private val clockRegex = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

    private fun validHours(start: String, end: String): Boolean =
        clockRegex.matches(start) && clockRegex.matches(end) && start < end

    fun readHours(value: JsonElement?): PlannerHours {
        val array = value as? JsonArray ?: return DEFAULT_HOURS
        if (array.size != 2) return DEFAULT_HOURS
        val start = (array[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return DEFAULT_HOURS
        val end = (array[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return DEFAULT_HOURS
        return if (validHours(start, end)) PlannerHours(start, end) else DEFAULT_HOURS
    }

    /** Whether a device may write [minutes] as `planner.default_length`. */
    fun isWritableLength(minutes: Int): Boolean = minutes in BLOCK_LENGTHS

    /** Whether a device may write [hours] as `planner.hours`. */
    fun isWritableHours(hours: PlannerHours): Boolean = validHours(hours.start, hours.end)
}

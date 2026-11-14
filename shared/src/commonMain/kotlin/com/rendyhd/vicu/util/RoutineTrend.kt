package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutineSchedule
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * A routine's trend (docs/cross-app-semantics-v1.md section 6.11): the state of each day for the
 * month of marks in its history, the run of done days, the last 30 days, twelve weeks of bars and
 * the summary line. Pure, the same rules as the desktop's `routine-trend.ts`; the `trend` vectors
 * of `routine-archive-v1.json` pin it.
 */
class RoutineTrend private constructor(
    private val definition: RoutineDefinition,
    private val today: LocalDate,
    /** The records that count on each day (a completion of an after-completion routine on the day it was done). */
    private val byDay: Map<LocalDate, List<RoutineOccurrenceRecord>>,
    /** The due date of an after-completion routine while it is due and neither done nor skipped; null otherwise. */
    private val openDue: LocalDate?,
    private val everCompleted: Boolean,
) {
    enum class DayState(val key: String) {
        NONE("none"),
        DONE("done"),
        PART("part"),
        SKIPPED("skipped"),
        MISSED("missed"),
        OPEN("open"),
    }

    /** A day's occurrences by what became of them. */
    data class Day(val done: Int = 0, val skipped: Int = 0, val missed: Int = 0, val open: Int = 0) {
        val state: DayState
            get() = when {
                done + skipped + missed + open == 0 -> DayState.NONE
                done + missed + open == 0 -> DayState.SKIPPED
                done > 0 && missed + open == 0 -> DayState.DONE
                done > 0 -> DayState.PART
                open > 0 -> DayState.OPEN
                else -> DayState.MISSED
            }
    }

    /** One time of the routine on a day: its status there, and the record behind it when there is one. */
    data class Occurrence(val slotId: String, val minutes: Int, val status: OccurrenceStatus, val record: RoutineOccurrenceRecord?)

    /** Completed occurrences, and how many were expected: the completed and the missed ones. */
    data class Tally(val done: Int, val expected: Int)

    private val calendar: RoutineSchedule.Calendar? = definition.schedule as? RoutineSchedule.Calendar
    private val activeFrom: LocalDate? = runCatching { LocalDate.parse(definition.activeFrom) }.getOrNull()

    /** The routine's first day: the earlier of `activeFrom` and its earliest record. */
    private val firstDay: LocalDate = listOfNotNull(activeFrom, byDay.keys.minOrNull()).minOrNull() ?: today

    /**
     * What [date] holds, in time order: for a schedule one occurrence per slot that ran or has a
     * record, for an after-completion routine what was done or skipped that day, or on today what
     * is due. This is also what the day picked lists under the month.
     */
    fun occurrencesOn(date: LocalDate): List<Occurrence> {
        if (date > today) return emptyList()
        val records = byDay[date].orEmpty()
        val schedule = calendar
        if (schedule == null) {
            val logged = records
                .filter { it.status == OccurrenceStatus.COMPLETED || it.status == OccurrenceStatus.SKIPPED }
                .map { Occurrence(it.slotId, it.scheduledMinutes, it.status, it) }
                .sortedBy { it.minutes }
            return when {
                logged.isNotEmpty() -> logged
                date == today && openDue != null ->
                    definition.slots.map { Occurrence(it.id, it.reminderMinutes, OccurrenceStatus.PENDING, null) }.sortedBy { it.minutes }
                else -> emptyList()
            }
        }
        val scheduled = (activeFrom == null || date >= activeFrom) && RoutineScheduleEngine.isScheduledOn(schedule, date)
        val slots = LinkedHashSet<String>()
        if (scheduled) definition.slots.forEach { slots += it.id }
        records.forEach { slots += it.slotId }
        return slots.map { slotId ->
            val record = records.firstOrNull { it.slotId == slotId }
            val stored = record?.status ?: OccurrenceStatus.PENDING
            Occurrence(
                slotId = slotId,
                minutes = definition.slots.firstOrNull { it.id == slotId }?.reminderMinutes ?: record?.scheduledMinutes ?: 0,
                // Without a record, or still pending, it is missed before today (section 6.7).
                status = if (stored == OccurrenceStatus.PENDING && date < today) OccurrenceStatus.NOT_LOGGED else stored,
                record = record,
            )
        }.sortedBy { it.minutes }
    }

    fun day(date: LocalDate): Day {
        val occurrences = occurrencesOn(date)
        return Day(
            done = occurrences.count { it.status == OccurrenceStatus.COMPLETED },
            skipped = occurrences.count { it.status == OccurrenceStatus.SKIPPED },
            missed = occurrences.count { it.status == OccurrenceStatus.NOT_LOGGED },
            open = occurrences.count { it.status == OccurrenceStatus.PENDING },
        )
    }

    fun state(date: LocalDate): DayState = day(date).state

    /** The run of done days up to today, or null for an after-completion routine, which has none. */
    val streak: Int? by lazy {
        if (calendar == null) return@lazy null
        var run = 0
        var date = today
        while (date >= firstDay) {
            when (state(date)) {
                DayState.DONE -> run++
                DayState.MISSED -> break
                DayState.PART -> if (date < today) break
                DayState.NONE, DayState.SKIPPED, DayState.OPEN -> {}
            }
            date = date.minus(1, DateTimeUnit.DAY)
        }
        run
    }

    private fun tally(from: LocalDate, to: LocalDate): Tally {
        var done = 0
        var missed = 0
        var date = maxOf(from, firstDay)
        val end = minOf(to, today)
        while (date <= end) {
            val day = day(date)
            done += day.done
            missed += day.missed
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return Tally(done, done + missed)
    }

    /** The last [WINDOW_DAYS] days, today included. Nothing is expected of an after-completion routine. */
    val window: Tally by lazy { tally(today.minus(WINDOW_DAYS - 1, DateTimeUnit.DAY), today) }

    /** The [WEEKS] Monday weeks that end with today's, oldest first; null for an after-completion routine. */
    val weeks: List<Tally>? by lazy {
        if (calendar == null) return@lazy null
        val thisMonday = today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        (WEEKS - 1 downTo 0).map { back ->
            val monday = thisMonday.minus(back * 7, DateTimeUnit.DAY)
            tally(monday, monday.plus(6, DateTimeUnit.DAY))
        }
    }

    /** The line over the month, the same words in both apps. */
    val summary: String by lazy {
        val last = window
        if (calendar == null) {
            when {
                !everCompleted -> NO_HISTORY
                last.done == 0 -> "Not done in the last $WINDOW_DAYS days"
                last.done == 1 -> "Done once in the last $WINDOW_DAYS days"
                else -> "Done ${last.done} times in the last $WINDOW_DAYS days"
            }
        } else if (last.done == 0 && last.expected == 0) {
            NO_HISTORY
        } else {
            val run = streak ?: 0
            (if (run >= 2) "$run in a row · " else "") + "${last.done} of ${last.expected} in the last $WINDOW_DAYS days"
        }
    }

    companion object {
        const val WINDOW_DAYS = 30
        const val WEEKS = 12
        const val NO_HISTORY = "No history yet"

        /**
         * [records] is the routine's whole history (the carrier and its archive parts), [today] the
         * local day and [zone] the device's zone, used for a record that names none.
         */
        fun of(
            definition: RoutineDefinition,
            records: Collection<RoutineOccurrenceRecord>,
            today: LocalDate,
            zone: TimeZone,
        ): RoutineTrend {
            val afterCompletion = definition.schedule as? RoutineSchedule.AfterCompletion
            val byDay = HashMap<LocalDate, MutableList<RoutineOccurrenceRecord>>()
            for (record in records) {
                val date = if (afterCompletion != null && record.status == OccurrenceStatus.COMPLETED) {
                    RoutineScheduleEngine.completionDate(record, zone)
                } else {
                    runCatching { LocalDate.parse(record.scheduledDate) }.getOrNull()
                } ?: continue
                byDay.getOrPut(date) { mutableListOf() } += record
            }
            val openDue = afterCompletion?.let { schedule ->
                val due = RoutineScheduleEngine.latestCompletionDate(records, zone)
                    ?.plus(schedule.intervalDays.coerceAtLeast(1), DateTimeUnit.DAY)
                    ?: runCatching { LocalDate.parse(schedule.firstDueDate) }.getOrNull()
                due?.takeIf { date ->
                    date <= today && records.none { it.status == OccurrenceStatus.SKIPPED && it.scheduledDate == date.toString() }
                }
            }
            return RoutineTrend(
                definition = definition,
                today = today,
                byDay = byDay,
                openDue = openDue,
                everCompleted = records.any { it.status == OccurrenceStatus.COMPLETED },
            )
        }
    }
}
